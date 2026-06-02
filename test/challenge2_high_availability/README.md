# 挑战二：高可用测试设计

## 核心机制（对应 PPT 挑战二）

```
优先级路由 → 三态熔断器 → 首包探测 → 切换备用模型 → 用户无感
(ModelSelector)  (ModelHealthStore)  (ProbeStreamBridge)  (ModelRoutingExecutor)
```

## 测试文件

| 文件 | 类型 | 目标 |
|------|------|------|
| `ModelCircuitBreakerTest.java` | 单元测试（无 Mock，真实对象） | 验证 ModelHealthStore 三态熔断器 + ModelRoutingExecutor 故障转移 |
| `ProbeStreamBridgeTest.java` | 单元测试（Mockito） | 验证 ProbeStreamBridge 首包探测与缓冲提交机制 |

## 测试用例矩阵

### ModelCircuitBreakerTest（8 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC01 | 初始状态 CLOSED | 首次调用 allowCall=true，isUnavailable=false |
| TC02 | 单次失败低于阈值 | 保持 CLOSED，allowCall=true |
| TC03 | 连续失败达阈值 | CLOSED → OPEN，allowCall=false，isUnavailable=true |
| TC04 | OPEN 期间持续拒绝 | 3 次 allowCall 均返回 false |
| TC05 | OPEN 到期 → HALF_OPEN | 首次 probe=true，第二次 in-flight 保护=false |
| TC06 | HALF_OPEN 成功 | → CLOSED，allowCall 恢复，计数重置 |
| TC07 | HALF_OPEN 失败 | → OPEN 重新熔断，halfOpenInFlight 重置后允许新 probe |
| TC08 | 路由故障转移 | 首选模型抛异常 → 自动切备用 → 返回备用响应 |

### ProbeStreamBridgeTest（6 个用例）

| ID | 名称 | 验证点 |
|----|------|--------|
| TC-P01 | onContent → SUCCESS | commit 后 downstream.onContent 被调用 |
| TC-P02 | onError → ERROR | 不 commit，downstream 零调用（防脏数据外泄） |
| TC-P03 | onComplete → NO_CONTENT | 不 commit，downstream 零调用 |
| TC-P04 | 无事件 → TIMEOUT | 超时返回，不阻塞，触发故障转移 |
| TC-P05 | 多 token 缓冲 | SUCCESS commit 后按序全量分发，无丢失 |
| TC-P06 | commit 后直接分发 | 后续 token 绕过 buffer，实时到达 downstream |

## 运行方式

```bash
# 1. 将文件移动到对应 Maven 模块的测试目录
cp ModelCircuitBreakerTest.java \
   ../../infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/model/

cp ProbeStreamBridgeTest.java \
   ../../infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/chat/

# 2. 运行三态熔断器 + 路由故障转移测试
cd ../../ && mvn test -pl infra-ai \
  -Dtest=ModelCircuitBreakerTest -DfailIfNoTests=false

# 3. 运行首包探测测试（需要 Mockito，已在父 pom 声明）
mvn test -pl infra-ai \
  -Dtest=ProbeStreamBridgeTest -DfailIfNoTests=false

# 4. 运行全部挑战二测试并生成 HTML 报告
bash test/challenge2_high_availability/run.sh ModelCircuitBreakerTest
bash test/challenge2_high_availability/run.sh ProbeStreamBridgeTest
```

## 关键设计决策

- **TC05 两次 allowCall 验证**：PPT 原文"HALF_OPEN 防止并发探测"。同一 ID 第二次 allowCall 返回 false（`halfOpenInFlight=true`），保证探测的单一性与结果的可信度。

- **TC07 in-flight 重置验证**：HALF_OPEN 失败后 `halfOpenInFlight` 被重置为 false，下一轮 OPEN 过期时允许新 probe。使用 `openDurationMs=-1000`（立即过期）让状态迁移可在同一测试内观察，避免依赖 `Thread.sleep`。

- **TC-P02/P03 防脏数据设计**：`verify(downstream, never())` 断言确认 ERROR 和 NO_CONTENT 时 downstream 零调用，体现 PPT 原文"首包探测，确认模型可用后再向用户推送，防止脏数据外泄"。

- **TC05 使用负数 openDurationMs**：`openDurationMs=-1000L` 使 `openUntil = now-1000`，保证条件 `openUntil > now` 为 false，OPEN 立即可视为过期，无需 `Thread.sleep`，测试确定性好。

- **ProbeStreamBridge 包私有类**：该类无 `public` 修饰，测试必须与其同包（`com.nageoffer.ai.ragent.infra.chat`），否则无法实例化。
