#!/usr/bin/env bash
# Ragent 分布式限流压测脚本
# 适配项目配置：max-concurrent=1, max-wait-seconds=3
# 需多用户并发（因 @IdempotentSubmit 同一用户不可并发）

set -uo pipefail

# ==================== 配置 ====================
BASE_URL="${BASE_URL:-http://localhost:9090/api/ragent}"
OUT_DIR="${OUT_DIR:-$(cd "$(dirname "$0")/../out" && pwd)}"
QUESTION="${QUESTION:-你好，请简单介绍一下自己}"
MAX_WAIT_SECS=12  # curl 超时：max-wait-seconds(3) + LLM响应(5~8s) + 余量
CONNECT_TIMEOUT=3

# ==================== 颜色 ====================
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

# ==================== 测试用户（与 test_users.sql 对应）====================
declare -a USERS=("admin:admin" "user1:password1" "user2:password2" "user3:password3"
                  "user4:password4" "user5:password5" "user6:password6" "user7:password7"
                  "user8:password8" "user9:password9" "user10:password10")

# ==================== 全局变量 ====================
declare -a TOKENS=()
declare -a LOGGED_USERS=()
RUN_ID=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="${OUT_DIR}/report_${RUN_ID}.json"
SUMMARY_FILE="${OUT_DIR}/summary_${RUN_ID}.txt"
LOG_DIR="${OUT_DIR}/logs_${RUN_ID}"
mkdir -p "$LOG_DIR"

# ==================== 工具函数 ====================
log_info()  { echo -e "${CYAN}[INFO]${RESET} $*"; }
log_ok()    { echo -e "${GREEN}[ OK ]${RESET} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${RESET} $*"; }
log_error() { echo -e "${RED}[ERR ]${RESET} $*"; }
log_phase() { echo -e "\n${BOLD}${BLUE}══════ $* ══════${RESET}"; }

ts_ms() { python3 -c "import time; print(int(time.time()*1000))" 2>/dev/null || date +%s%3N; }

# ==================== 服务健康检查 ====================
check_service() {
    log_phase "服务连通性检查"
    local raw_code
    raw_code=$(curl -s -o /dev/null -w "%{http_code}" \
        --connect-timeout "$CONNECT_TIMEOUT" \
        "${BASE_URL}/auth/login" -X POST \
        -H "Content-Type: application/json" \
        -d '{"username":"__health_check__","password":"__x__"}' 2>/dev/null)
    # curl 连接失败时输出 "000"，取最后3位排除多余字符
    local code="${raw_code: -3}"
    if [[ "$code" == "000" || -z "$code" ]]; then
        log_error "无法连接到 ${BASE_URL}，请先启动后端服务"
        echo ""
        echo "  请依次启动以下服务："
        echo "  1. Redis:      brew services start redis"
        echo "  2. PostgreSQL: brew services start postgresql@14"
        echo "  3. 后端:       cd ragent-main && ./mvnw spring-boot:run -pl bootstrap"
        exit 1
    fi
    log_ok "服务在线 (HTTP ${code})"
}

# ==================== 登录 ====================
login_user() {
    local username="$1" password="$2"
    local resp token
    resp=$(curl -s --connect-timeout "$CONNECT_TIMEOUT" \
        "${BASE_URL}/auth/login" -X POST \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"${username}\",\"password\":\"${password}\"}" 2>/dev/null)
    token=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('token',''))" 2>/dev/null)
    echo "$token"
}

login_all_users() {
    log_phase "用户登录"
    for entry in "${USERS[@]}"; do
        local u="${entry%%:*}" p="${entry##*:}"
        local tok
        tok=$(login_user "$u" "$p")
        if [[ -n "$tok" ]]; then
            TOKENS+=("$tok")
            LOGGED_USERS+=("$u")
            log_ok "用户 ${u} 登录成功"
        else
            log_warn "用户 ${u} 登录失败（可能不存在，跳过）"
        fi
    done

    if [[ ${#TOKENS[@]} -eq 0 ]]; then
        log_error "所有用户登录失败，请先执行："
        echo "  psql -U postgres -d ragent -f resources/database/test_users.sql"
        exit 1
    fi
    log_info "可用用户数: ${#TOKENS[@]}"
}

# ==================== 单次请求 ====================
# 返回: status_code|response_time_ms|body_bytes
send_chat_request() {
    local token="$1" username="$2" extra_label="$3"
    local t_start t_end status body_size
    local log_file="${LOG_DIR}/${extra_label}_${username}.log"

    t_start=$(ts_ms)
    local http_resp
    http_resp=$(curl -s -N \
        --connect-timeout "$CONNECT_TIMEOUT" \
        --max-time "$MAX_WAIT_SECS" \
        -w "\n__HTTP_STATUS__%{http_code}__" \
        -H "Authorization: ${token}" \
        --get \
        --data-urlencode "question=${QUESTION}" \
        --data-urlencode "deepThinking=false" \
        "${BASE_URL}/rag/v3/chat" 2>&1)
    t_end=$(ts_ms)

    echo "$http_resp" > "$log_file"

    local rt=$((t_end - t_start))
    local status_code
    status_code=$(echo "$http_resp" | grep -o '__HTTP_STATUS__[0-9]*__' | grep -o '[0-9]*' | tail -1)
    status_code="${status_code:-000}"
    local body_bytes
    body_bytes=$(wc -c < "$log_file" 2>/dev/null || echo 0)

    # 判断是否触发了限流拒绝（SSE event:reject 事件）
    local rejected=0
    grep -q 'event:reject\|系统繁忙' "$log_file" 2>/dev/null && rejected=1

    # 验证 SSE 是否真正收到内容（而非空 200）
    local has_sse=0
    grep -q 'event:meta\|event:message' "$log_file" 2>/dev/null && has_sse=1

    echo "${status_code}|${rt}|${body_bytes}|${rejected}|${has_sse}"
}

# ==================== 并发测试执行 ====================
run_concurrent() {
    local phase_name="$1"
    local concurrency="$2"
    shift 2
    local user_indices=("$@")  # 用哪些用户的索引

    log_phase "${phase_name}（并发=${concurrency}）"

    local -a pids=()
    local tmp_dir="${LOG_DIR}/tmp_${phase_name// /_}"
    mkdir -p "$tmp_dir"

    local t_start
    t_start=$(ts_ms)

    for idx in "${user_indices[@]}"; do
        local tok="${TOKENS[$idx]}"
        local user="${LOGGED_USERS[$idx]}"
        local result_file="${tmp_dir}/result_${idx}.txt"

        (
            result=$(send_chat_request "$tok" "$user" "${phase_name// /_}")
            echo "$result" > "$result_file"
        ) &
        pids+=($!)
    done

    # 等待所有子进程
    for pid in "${pids[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local t_end
    t_end=$(ts_ms)
    local total_rt=$((t_end - t_start))

    # 统计
    local total=0 success=0 rejected=0 failed=0 timeout=0
    local total_resp_ms=0
    local min_rt=999999 max_rt=0
    declare -a rt_list=()

    for idx in "${user_indices[@]}"; do
        local result_file="${tmp_dir}/result_${idx}.txt"
        if [[ -f "$result_file" ]]; then
            local line
            line=$(cat "$result_file")
            local sc rt bs rej has_sse
            sc=$(echo "$line" | cut -d'|' -f1)
            rt=$(echo "$line" | cut -d'|' -f2)
            bs=$(echo "$line" | cut -d'|' -f3)
            rej=$(echo "$line" | cut -d'|' -f4)
            has_sse=$(echo "$line" | cut -d'|' -f5)

            total=$((total + 1))
            rt_list+=("$rt")
            total_resp_ms=$((total_resp_ms + rt))
            [[ $rt -lt $min_rt ]] && min_rt=$rt
            [[ $rt -gt $max_rt ]] && max_rt=$rt

            if [[ "$rej" == "1" ]]; then
                rejected=$((rejected + 1))
                log_warn "用户 ${LOGGED_USERS[$idx]}: 限流拒绝 (${rt}ms)"
            elif [[ "$sc" == "200" && "$has_sse" == "1" ]]; then
                success=$((success + 1))
                log_ok "用户 ${LOGGED_USERS[$idx]}: SSE正常 HTTP ${sc} (${rt}ms, ${bs}B)"
            elif [[ "$sc" == "000" ]]; then
                timeout=$((timeout + 1))
                log_error "用户 ${LOGGED_USERS[$idx]}: 超时无响应 (${rt}ms) — 服务未在 ${MAX_WAIT_SECS}s 内回复"
            else
                failed=$((failed + 1))
                log_error "用户 ${LOGGED_USERS[$idx]}: 异常 HTTP ${sc} (${rt}ms)"
            fi
        fi
    done

    local avg_rt=0
    [[ $total -gt 0 ]] && avg_rt=$((total_resp_ms / total))

    # 计算 P95（排序后取 95% 位置）
    local p95_rt=0
    if [[ ${#rt_list[@]} -gt 0 ]]; then
        local sorted_rt
        sorted_rt=$(printf '%s\n' "${rt_list[@]}" | sort -n)
        local p95_idx=$(( (${#rt_list[@]} * 95 / 100) ))
        [[ $p95_idx -ge ${#rt_list[@]} ]] && p95_idx=$((${#rt_list[@]} - 1))
        p95_rt=$(echo "$sorted_rt" | sed -n "$((p95_idx+1))p")
    fi

    local rate_limit_pct=0
    [[ $total -gt 0 ]] && rate_limit_pct=$((rejected * 100 / total))

    echo ""
    echo -e "${BOLD}  [ ${phase_name} 结果 ]${RESET}"
    echo -e "  总请求数    : ${total}"
    echo -e "  成功放行    : ${GREEN}${success}${RESET}"
    echo -e "  限流拒绝    : ${YELLOW}${rejected}${RESET} (${rate_limit_pct}%)"
    echo -e "  超时无响应  : ${RED}${timeout}${RESET}"
    echo -e "  其他失败    : ${RED}${failed}${RESET}"
    echo -e "  总耗时      : ${total_rt}ms"
    echo -e "  平均响应    : ${avg_rt}ms"
    echo -e "  最小/最大   : ${min_rt}ms / ${max_rt}ms"
    echo -e "  P95 响应    : ${p95_rt}ms"

    # 输出 JSON 片段（actual_concurrency 记录实际用户数）
    cat >> "$REPORT_FILE" <<JSON
  {
    "phase": "${phase_name}",
    "concurrency_requested": ${concurrency},
    "concurrency_actual": ${total},
    "total": ${total},
    "success": ${success},
    "rejected": ${rejected},
    "timeout": ${timeout},
    "failed": ${failed},
    "rate_limit_pct": ${rate_limit_pct},
    "wall_time_ms": ${total_rt},
    "avg_resp_ms": ${avg_rt},
    "min_resp_ms": ${min_rt},
    "max_resp_ms": ${max_rt},
    "p95_resp_ms": ${p95_rt}
  },
JSON
}

# ==================== 主流程 ====================
main() {
    echo -e "${BOLD}${CYAN}"
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║      Ragent 分布式限流压测  (rate_limit_load_test)   ║"
    echo "╚══════════════════════════════════════════════════════╝"
    echo -e "${RESET}"
    echo -e "  服务地址  : ${YELLOW}${BASE_URL}${RESET}"
    echo -e "  输出目录  : ${YELLOW}${OUT_DIR}${RESET}"
    echo -e "  限流配置  : max-concurrent=1, max-wait=3s (application.yaml)"
    echo ""

    check_service
    login_all_users

    local n=${#TOKENS[@]}
    log_info "开始压测，共 ${n} 个有效用户"

    # 初始化报告 JSON
    cat > "$REPORT_FILE" <<JSON
{
  "run_id": "${RUN_ID}",
  "base_url": "${BASE_URL}",
  "question": "${QUESTION}",
  "rate_limit_config": {
    "max_concurrent": 1,
    "max_wait_seconds": 3,
    "algorithm": "Redis RPermitExpirableSemaphore + SortedSet Queue"
  },
  "phases": [
JSON

    # ==================== Phase 1: 单用户基准 ====================
    log_phase "Phase 1 — 单用户基准（串行 3 次）"
    local p1_success=0 p1_failed=0
    local p1_rts=()
    for i in 1 2 3; do
        local result
        result=$(send_chat_request "${TOKENS[0]}" "${LOGGED_USERS[0]}" "p1_serial_${i}")
        local sc rt rej has_sse
        sc=$(echo "$result" | cut -d'|' -f1)
        rt=$(echo "$result" | cut -d'|' -f2)
        rej=$(echo "$result" | cut -d'|' -f4)
        has_sse=$(echo "$result" | cut -d'|' -f5)
        p1_rts+=("$rt")
        if [[ "$rej" == "1" ]]; then
            log_warn "第${i}次: 限流拒绝 (${rt}ms)"; p1_failed=$((p1_failed+1))
        elif [[ "$sc" == "200" && "$has_sse" == "1" ]]; then
            log_ok "第${i}次: SSE正常 HTTP ${sc} (${rt}ms)"; p1_success=$((p1_success+1))
        else
            log_error "第${i}次: 异常 HTTP ${sc} has_sse=${has_sse} (${rt}ms)"; p1_failed=$((p1_failed+1))
        fi
        sleep 1
    done
    local p1_avg=0
    [[ ${#p1_rts[@]} -gt 0 ]] && p1_avg=$(( (${p1_rts[0]} + ${p1_rts[1]:-0} + ${p1_rts[2]:-0}) / ${#p1_rts[@]} ))

    cat >> "$REPORT_FILE" <<JSON
  {
    "phase": "Phase 1 - 单用户串行基准",
    "concurrency_requested": 1,
    "concurrency_actual": 1,
    "total": 3,
    "success": ${p1_success},
    "rejected": 0,
    "failed": ${p1_failed},
    "rate_limit_pct": 0,
    "avg_resp_ms": ${p1_avg},
    "note": "串行3次，每次间隔1s"
  },
JSON

    # ==================== Phase 2: 并发数 <= max-concurrent ====================
    # max-concurrent=1，所以并发1个用户不触发限流
    local p2_count=1
    local p2_indices=(0)
    run_concurrent "Phase 2 - 并发1（等于限额）" $p2_count "${p2_indices[@]}"

    sleep 2

    # ==================== Phase 3: 并发数 = 2（超出限额 1 倍）====================
    local p3_count=2
    local p3_indices
    p3_indices=($(seq 0 $((p3_count > n ? n-1 : p3_count-1))))
    run_concurrent "Phase 3 - 并发2（超出限额）" $p3_count "${p3_indices[@]}"

    sleep 2

    # ==================== Phase 4: 并发数 = 5 ====================
    local p4_count=5
    if [[ $n -ge $p4_count ]]; then
        local p4_indices=($(seq 0 $((p4_count-1))))
        run_concurrent "Phase 4 - 并发5（高压）" $p4_count "${p4_indices[@]}"
        sleep 2
    else
        log_warn "Phase 4 跳过（用户数不足 5 个，当前 ${n} 个）"
    fi

    # ==================== Phase 5: 全量用户冲压 ====================
    if [[ $n -ge 3 ]]; then
        local p5_indices=($(seq 0 $((n-1))))
        run_concurrent "Phase 5 - 全量用户并发（${n}人）" $n "${p5_indices[@]}"
    fi

    # ==================== 完成报告 ====================
    # 移除最后一个逗号并关闭 JSON
    local tmp_report="${REPORT_FILE}.tmp"
    python3 - "$REPORT_FILE" "$tmp_report" <<'PY'
import sys, json, re
src, dst = sys.argv[1], sys.argv[2]
with open(src) as f:
    content = f.read()
# 移除最后一个多余逗号
content = re.sub(r',\s*\n(\s*\])', r'\n\1', content)
# 尝试解析，若失败则追加关闭括号
try:
    obj = json.loads(content + "\n  ]\n}")
    with open(dst, 'w') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
except Exception:
    with open(dst, 'w') as f:
        f.write(content + "\n  ]\n}\n")
PY
    [[ -f "$tmp_report" ]] && mv "$tmp_report" "$REPORT_FILE"

    # 生成文字摘要
    cat > "$SUMMARY_FILE" <<TXT
Ragent 限流压测摘要
===================
Run ID    : ${RUN_ID}
时间      : $(date '+%Y-%m-%d %H:%M:%S')
服务地址  : ${BASE_URL}
有效用户  : ${n}

限流配置
---------
算法      : Redis RPermitExpirableSemaphore + SortedSet 公平队列
实现      : ChatQueueLimiter（Redisson 分布式信号量）
max-concurrent  : 1
max-wait-seconds: 3
lease-seconds   : 30
poll-interval-ms: 200

结论
---------
- Phase 2（并发=限额）: 所有请求应成功放行
- Phase 3（并发=2）  : 1个立即执行，1个进入排队，若超过 max-wait=3s 则被拒绝
- Phase 4/5（高并发）: 只有1个并发执行，其余在3s内等不到则返回"系统繁忙"

输出文件
---------
JSON报告  : ${REPORT_FILE}
本摘要    : ${SUMMARY_FILE}
请求日志  : ${LOG_DIR}/
TXT

    echo ""
    echo -e "${GREEN}${BOLD}✅ 压测完成！${RESET}"
    echo -e "  JSON 报告 : ${CYAN}${REPORT_FILE}${RESET}"
    echo -e "  文字摘要  : ${CYAN}${SUMMARY_FILE}${RESET}"
    echo -e "  请求日志  : ${CYAN}${LOG_DIR}/${RESET}"
}

main "$@"
