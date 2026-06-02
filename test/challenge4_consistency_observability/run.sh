#!/usr/bin/env bash
# 运行挑战四测试并生成详细 HTML 报告
# 用法：bash test/challenge4_consistency_observability/run.sh [IntentTreeCacheManagerTest|RagTraceContextTest]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_CLASS="${1:-IntentTreeCacheManagerTest}"
METADATA_JSON="$SCRIPT_DIR/metadata.json"

# 根据测试类确定包路径和报告路径
case "$TEST_CLASS" in
  IntentTreeCacheManagerTest)
    PKG="com.nageoffer.ai.ragent.rag.core.intent"
    ;;
  RagTraceContextTest)
    PKG="com.nageoffer.ai.ragent.framework.trace"
    ;;
  *)
    echo "未知测试类：$TEST_CLASS"; exit 1
    ;;
esac

REPORT_XML="$ROOT_DIR/bootstrap/target/surefire-reports/TEST-${PKG}.${TEST_CLASS}.xml"
REPORT_HTML="$ROOT_DIR/bootstrap/target/surefire-reports/${TEST_CLASS}-report.html"

JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null)
export JAVA_HOME

echo "▶ 运行测试：${TEST_CLASS}"

cd "$ROOT_DIR"
mvn test -pl bootstrap \
    -Dtest="$TEST_CLASS" \
    -DfailIfNoTests=false \
    -q 2>&1 || true

if [[ ! -f "$REPORT_XML" ]]; then
    echo "错误：未找到报告文件 $REPORT_XML"; exit 1
fi

python3 - "$REPORT_XML" "$METADATA_JSON" "$TEST_CLASS" "$REPORT_HTML" <<'PYEOF'
import sys, json, html, datetime
import xml.etree.ElementTree as ET

xml_path, meta_path, class_name, html_path = sys.argv[1:]

root  = ET.parse(xml_path).getroot()
meta  = json.load(open(meta_path, encoding="utf-8")).get(class_name, {})
cases = meta.get("cases", {})

suite_name = meta.get("title", class_name)
suite_desc = meta.get("description", "")
total    = int(root.attrib.get("tests",    0))
failures = int(root.attrib.get("failures", 0))
errors   = int(root.attrib.get("errors",   0))
skipped  = int(root.attrib.get("skipped",  0))
elapsed  = float(root.attrib.get("time",   0))
passed   = total - failures - errors - skipped
has_fail = (failures + errors) > 0

def sort_key(item):
    m = cases.get(item.attrib["name"], {})
    return m.get("id", "ZZ")

sorted_tcs = sorted(root.findall("testcase"), key=sort_key)

STYLE = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system,"Segoe UI",Helvetica,sans-serif;
       background: #f0f2f5; color: #1a1a2e; padding: 32px; font-size: 14px; }
a { color: inherit; text-decoration: none; }

.header { background: #1a1a2e; color: #fff; border-radius: 12px;
          padding: 28px 32px; margin-bottom: 24px; }
.header h1 { font-size: 1.4rem; font-weight: 700; margin-bottom: 6px; }
.header .desc { color: #a0aec0; font-size: .82rem; line-height: 1.6; max-width: 780px; }
.header .meta { margin-top: 12px; font-size: .78rem; color: #718096; }

.cards { display: flex; gap: 14px; margin-bottom: 24px; flex-wrap: wrap; }
.card { background: #fff; border-radius: 10px; padding: 18px 24px; min-width: 110px;
        box-shadow: 0 1px 4px rgba(0,0,0,.07); text-align: center; flex: 1; max-width: 160px; }
.card .val { font-size: 2.2rem; font-weight: 700; line-height: 1.1; }
.card .lbl { font-size: .72rem; color: #718096; margin-top: 5px; text-transform: uppercase; letter-spacing: .05em; }
.c-total { color: #2d3748; }
.c-pass  { color: #276749; }
.c-fail  { color: #c53030; }
.c-skip  { color: #b7791f; }
.c-time  { color: #2b6cb0; }

.badge { display: inline-flex; align-items: center; gap: 8px;
         padding: 6px 18px; border-radius: 24px; font-size: .85rem;
         font-weight: 600; margin-bottom: 24px; color: #fff; }
.badge-pass { background: #276749; }
.badge-fail { background: #c53030; }

.tc-card { background: #fff; border-radius: 10px; margin-bottom: 16px;
           box-shadow: 0 1px 4px rgba(0,0,0,.07); overflow: hidden;
           border-left: 5px solid #ccc; }
.tc-card.pass { border-left-color: #48bb78; }
.tc-card.fail { border-left-color: #fc8181; }
.tc-card.skip { border-left-color: #f6ad55; }

.tc-header { display: flex; align-items: center; gap: 12px;
             padding: 14px 20px; cursor: pointer; user-select: none; }
.tc-header:hover { background: #f7fafc; }
.tc-id   { background: #edf2f7; color: #4a5568; font-weight: 700;
           font-size: .75rem; padding: 3px 9px; border-radius: 5px;
           min-width: 56px; text-align: center; letter-spacing: .03em; }
.tc-name { font-weight: 600; font-size: .93rem; flex: 1; }
.tc-time { color: #718096; font-size: .78rem; white-space: nowrap; }
.tag { padding: 3px 12px; border-radius: 5px; font-size: .78rem;
       font-weight: 700; white-space: nowrap; }
.tag.pass { background: #c6f6d5; color: #22543d; }
.tag.fail { background: #fed7d7; color: #742a2a; }
.tag.skip { background: #fefcbf; color: #744210; }
.arrow { font-size: .7rem; color: #a0aec0; transition: transform .2s; }
.open .arrow { transform: rotate(180deg); }

.tc-body { display: none; border-top: 1px solid #edf2f7; }
.tc-body.visible { display: block; }

.section { padding: 18px 24px; }
.section + .section { border-top: 1px solid #edf2f7; }
.section h3 { font-size: .7rem; text-transform: uppercase; letter-spacing: .08em;
              color: #a0aec0; margin-bottom: 10px; font-weight: 700; }
.section p  { color: #4a5568; line-height: 1.7; font-size: .85rem; }

.metrics { width: 100%; border-collapse: collapse; margin-top: 4px; }
.metrics th { text-align: left; font-size: .72rem; text-transform: uppercase;
              letter-spacing: .05em; color: #a0aec0; padding: 5px 10px 5px 0; border-bottom: 1px solid #edf2f7; }
.metrics td { padding: 7px 10px 7px 0; font-size: .83rem;
              border-bottom: 1px solid #f7fafc; vertical-align: top; }
.metrics tr:last-child td { border-bottom: none; }
.m-label { color: #4a5568; font-weight: 500; width: 220px; }
.m-val   { color: #2b6cb0; font-weight: 700; font-family: "SFMono-Regular",Consolas,monospace;
           width: 140px; }
.m-note  { color: #718096; font-size: .78rem; }

.fail-box { background: #fff5f5; border: 1px solid #fed7d7; border-radius: 8px;
            padding: 14px 18px; }
.fail-box .err-type { font-size: .78rem; color: #c53030; font-weight: 700;
                      text-transform: uppercase; margin-bottom: 6px; }
.fail-box pre { font-family: "SFMono-Regular",Consolas,monospace; font-size: .78rem;
                color: #742a2a; white-space: pre-wrap; word-break: break-all;
                max-height: 260px; overflow-y: auto; }

.method-name { font-family: "SFMono-Regular",Consolas,monospace; font-size: .75rem;
               color: #718096; }
"""

SCRIPT = """
function toggle(id) {
    var body = document.getElementById(id);
    var hdr  = body.previousElementSibling;
    body.classList.toggle('visible');
    hdr.classList.toggle('open');
}
"""

def status_info(tc):
    f = tc.find("failure"); e = tc.find("error"); s = tc.find("skipped")
    if f is not None: return "fail", "FAIL", f
    if e is not None: return "fail", "FAIL", e
    if s is not None: return "skip", "SKIP", None
    return "pass", "PASS", None

def make_fail_html(node):
    if node is None: return ""
    msg   = html.escape(node.attrib.get("message","") or "")
    trace = html.escape(node.text or "")
    return f"""
<div class="section">
  <h3>失败详情</h3>
  <div class="fail-box">
    <div class="err-type">{html.escape(node.attrib.get("type",""))}</div>
    <pre>{msg}\n\n{trace}</pre>
  </div>
</div>"""

def make_metrics_html(metric_list):
    if not metric_list: return ""
    rows = "".join(
        f"<tr><td class='m-label'>{html.escape(m['label'])}</td>"
        f"<td class='m-val'>{html.escape(m['expected'])}</td>"
        f"<td class='m-note'>{html.escape(m.get('note',''))}</td></tr>"
        for m in metric_list
    )
    return f"""
<div class="section">
  <h3>关键指标与断言阈值</h3>
  <table class="metrics">
    <thead><tr><th>指标</th><th>期望值 / 约束</th><th>说明</th></tr></thead>
    <tbody>{rows}</tbody>
  </table>
</div>"""

cards_html = ""
for idx, tc in enumerate(sorted_tcs):
    method   = tc.attrib["name"]
    time_s   = float(tc.attrib.get("time", 0))
    cls, lbl, fail_node = status_info(tc)
    m = cases.get(method, {})

    tc_id      = m.get("id", f"#{idx+1}")
    display    = m.get("display", method)
    what_text  = m.get("what", "")
    how_text   = m.get("how", "")
    metrics    = m.get("metrics", [])
    body_id    = f"body_{idx}"

    what_sec = f"""
<div class="section">
  <h3>验证点 — 检测什么</h3>
  <p>{html.escape(what_text)}</p>
</div>""" if what_text else ""

    how_sec = f"""
<div class="section">
  <h3>验证方式 — 如何通过</h3>
  <p>{html.escape(how_text)}</p>
</div>""" if how_text else ""

    cards_html += f"""
<div class="tc-card {cls}">
  <div class="tc-header" onclick="toggle('{body_id}')">
    <span class="tc-id">{html.escape(tc_id)}</span>
    <span class="tc-name">{html.escape(display)}</span>
    <span class="tc-time">{time_s:.3f} s</span>
    <span class="tag {cls}">{lbl}</span>
    <span class="arrow">▼</span>
  </div>
  <div class="tc-body" id="{body_id}">
    <div class="section" style="padding-bottom:8px">
      <h3>方法名</h3>
      <span class="method-name">{html.escape(method)}()</span>
    </div>
    {what_sec}
    {how_sec}
    {make_metrics_html(metrics)}
    {make_fail_html(fail_node)}
  </div>
</div>"""

now      = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
b_cls    = "badge-pass" if not has_fail else "badge-fail"
b_icon   = "✅" if not has_fail else "❌"
b_text   = f"全部 {passed} 个用例通过" if not has_fail else f"{failures+errors} 个用例失败"
pass_pct = f"{passed/total*100:.0f}" if total else "0"

page = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(suite_name)}</title>
<style>{STYLE}</style>
</head>
<body>

<div class="header">
  <h1>🧪 {html.escape(suite_name)}</h1>
  <div class="desc">{html.escape(suite_desc)}</div>
  <div class="meta">生成时间：{now} &nbsp;|&nbsp; 总耗时：{elapsed:.2f} s &nbsp;|&nbsp; 通过率：{pass_pct}%</div>
</div>

<div class="cards">
  <div class="card"><div class="val c-total">{total}</div><div class="lbl">总用例</div></div>
  <div class="card"><div class="val c-pass">{passed}</div><div class="lbl">通过</div></div>
  <div class="card"><div class="val c-fail">{failures+errors}</div><div class="lbl">失败</div></div>
  <div class="card"><div class="val c-skip">{skipped}</div><div class="lbl">跳过</div></div>
  <div class="card"><div class="val c-time">{elapsed:.1f}s</div><div class="lbl">总耗时</div></div>
</div>

<div class="badge {b_cls}">{b_icon} {html.escape(b_text)}</div>

{cards_html}

<script>{SCRIPT}</script>
</body>
</html>"""

with open(html_path, "w", encoding="utf-8") as f:
    f.write(page)
print(f"报告已生成：{html_path}")
PYEOF

open "$REPORT_HTML"
