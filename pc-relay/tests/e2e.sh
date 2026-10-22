#!/usr/bin/env bash
# pc-relay 端到端联调：自起 mock 手机 + relay，覆盖鉴权/解析/转发/重试/不可达/应用化流程。
# v2.1 起配置存 SQLite（PC_RELAY_DB 指向 relay.db），本脚本全部用临时 DB，不动真实数据。
# 输出紧凑 PASS/FAIL 摘要。依赖：bash、curl、项目 venv。
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"
PY="$DIR/.venv/bin/python"
BASE=http://127.0.0.1:8787
pass=0; fail=0
MOCK_PID=""; RELAY_PID=""; RELAY2_PID=""; RELAY3_PID=""

chk() { if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "PASS $1"; else fail=$((fail+1)); echo "FAIL $1 (期望$2 实际$3)"; fi; }
code() { curl -s -o "$DIR/tests/logs/last_body.json" -w '%{http_code}' "$@"; }
cleanup() {
  for p in "$MOCK_PID" "$RELAY_PID" "$RELAY2_PID" "$RELAY3_PID"; do
    [ -n "$p" ] && kill $p 2>/dev/null
  done
}
trap cleanup EXIT

LOGS="$DIR/tests/logs"; mkdir -p "$LOGS"

# helper：直接读测试 DB 的 kv 表（python sqlite3，不依赖 sqlite3 CLI）
dbkv() { "$PY" - "$1" "$2" <<'EOF'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
row = con.execute("SELECT value FROM kv WHERE key=?", (sys.argv[2],)).fetchone()
print(row[0] if row else "")
EOF
}
# （起服务前需预置 phone.* 时，直接用 $PY + sqlite3 建表插入，见下方 DB_MAIN 预置块）

# 0) Web UI 渲染检查（无浏览器，需要 node；未找到则跳过）
#    专抓「定义了但没被调用」的静默 bug：例如设置页的「开机自启」区块依赖
#    onMounted 里调用 loadAutostart()，漏调用时 v-if 把区块整个吞掉，页面上什么都看不到，
#    而 /admin/api/autostart 接口本身是好的（后端用例全绿）。SSR 不执行 onMounted，抓不到这种问题。
NODE_BIN="${NODE_BIN:-$(command -v node 2>/dev/null || true)}"
if [ -n "$NODE_BIN" ]; then
  RC_OUT="$( (cd webui && "$NODE_BIN" tests/render_check.mjs 2>/dev/null) | tail -1 )"
  RC_FAIL="$(printf '%s' "$RC_OUT" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')"
  chk "Web UI 渲染检查（开机自启区块 / 眼睛图标）" "0" "${RC_FAIL:-未运行}"
else
  echo "SKIP Web UI 渲染检查（未找到 node；可用 NODE_BIN=/path/to/node 指定）"
fi

# 1) 启动 mock 手机 + relay（独立临时 DB，与真实数据隔离）
WT=""   # 由首启种子生成，稍后从 DB 读
DB_MAIN="$LOGS/relay_e2e.db"; rm -f "$DB_MAIN"*
# 预置 phone 指向 mock（webhook.token 留给首启种子随机生成，起服务后从 DB 读）
$PY - "$DB_MAIN" <<'EOF'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
con.execute("CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT)")
con.executemany("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", [
    ("phone.url", "http://127.0.0.1:8799"), ("phone.token", "test-phone-token"),
])
con.commit()
EOF
MOCK_LOG=$LOGS/mock_phone.log; RELAY_LOG=$LOGS/pc_relay.log
rm -f $MOCK_LOG $RELAY_LOG
"$PY" tests/mock_phone.py 8799 >$MOCK_LOG 2>&1 & MOCK_PID=$!
PC_RELAY_DB="$DB_MAIN" PC_RELAY_PORT=8787 "$PY" -m uvicorn main:app --host 127.0.0.1 --port 8787 >$RELAY_LOG 2>&1 & RELAY_PID=$!
for i in $(seq 1 100); do curl -sf $BASE/health >/dev/null 2>&1 && break; sleep 0.3; done

# 首启自动建库 + 种子默认值
chk "首启自动建库" 1 "$(test -f "$DB_MAIN" && echo 1 || echo 0)"
WT="$(dbkv "$DB_MAIN" webhook.token)"
chk "种子 webhook token 64hex" 1 "$("$PY" -c "import re,sys;print(1 if re.fullmatch(r'[0-9a-f]{64}',sys.argv[1]) else 0)" "$WT")"

# 2) 基础用例
chk "health 探活 200" 200 "$(code $BASE/health)"
chk "无token 401" 401 "$(code -X POST $BASE/webhook -H 'Content-Type: application/json' -d '{"title":"a","body":"b"}')"
chk "错token 401" 401 "$(code -X POST $BASE/webhook -H 'Authorization: Bearer wrong' -d '{"title":"a","body":"b"}')"
chk "坏JSON 400" 400 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' -d '{bad')"
chk "缺body 400" 400 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' -d '{"title":"only"}')"
chk "JSON送达 200" 200 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' -d '{"title":"Grafana","body":"CPU high","source":"grafana","priority":"high"}')"
chk "查询参数 200" 200 "$(code -X POST "$BASE/webhook?title=backup&body=done" -H "Authorization: Bearer $WT")"
chk "纯文本 200" 200 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: text/plain' --data-binary 'plain alert')"
big=$("$PY" -c "print('x'*70000)")
chk "超大 413" 413 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: text/plain' --data-binary "$big")"

# 3) 5xx 重试：mock 前 1 次返回 500，重试后应成功
kill $MOCK_PID 2>/dev/null; wait $MOCK_PID 2>/dev/null
MOCK_FAIL_FIRST=1 "$PY" tests/mock_phone.py 8799 >>$MOCK_LOG 2>&1 & MOCK_PID=$!
sleep 0.5
chk "5xx重试后成功 200" 200 "$(code -X POST $BASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' -d '{"title":"retry","body":"case"}')"

# 4) 不可达：relay 指向空端口 → 502（重试耗尽约需 4s）
DB_DEAD="$LOGS/relay_dead.db"; rm -f "$DB_DEAD"*
PC_RELAY_DB="$DB_DEAD" PC_RELAY_PORT=8788 "$PY" -m uvicorn main:app --host 127.0.0.1 --port 8788 >"$LOGS/pc_relay_dead.log" 2>&1 & RELAY2_PID=$!
for i in $(seq 1 100); do curl -sf http://127.0.0.1:8788/health >/dev/null 2>&1 && break; sleep 0.3; done
# 死端口实例 needs_setup=true（phone 未配置），webhook 鉴权仍生效
WT_DEAD="$(dbkv "$DB_DEAD" webhook.token)"
code -X POST http://127.0.0.1:8788/webhook -H "Authorization: Bearer $WT_DEAD" -H 'Content-Type: application/json' -d '{"title":"x","body":"y"}' >/dev/null
t0=$(date +%s)
chk "死端口 502" 502 "$(code -X POST http://127.0.0.1:8788/webhook -H "Authorization: Bearer $WT_DEAD" -H 'Content-Type: application/json' -d '{"title":"x","body":"y"}')"
echo "死端口耗时 $(( $(date +%s) - t0 ))s（≥3s 证明有重试等待）"

# 5) v2.1 应用化流程：首启免密登录 → 表单保存热生效 → 重启 → 会话保持
#    （用独立端口 8789 + 独立 DB，避免污染上面的用例）
AB=http://127.0.0.1:8789
DB_APP="$LOGS/relay_e2e_app.db"; rm -f "$DB_APP"*
PC_RELAY_DB="$DB_APP" PC_RELAY_PORT=8789 "$PY" main.py >"$LOGS/pc_relay_app.log" 2>&1 & RELAY3_PID=$!
trap 'cleanup; [ -n "$RELAY3_PID" ] && kill $RELAY3_PID 2>/dev/null' EXIT
for i in $(seq 1 100); do curl -sf $AB/health >/dev/null 2>&1 && break; sleep 0.3; done

chk "应用实例建库" 1 "$(test -f "$DB_APP" && echo 1 || echo 0)"
# needs_setup 时免密登录（setup 向导入口）
chk "setup 免密登录 200" 200 "$(code -c "$LOGS/ck.txt" -X POST $AB/admin/login -H 'Content-Type: application/json' -d '{"password":""}')"
# 表单保存：手机+密码（热生效，密码哈希落库）
chk "表单保存 200" 200 "$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/config -H 'Content-Type: application/json' -d '{"values":{"phone":{"url":"http://127.0.0.1:8799","token":"test-phone-token"},"admin":{"password":"e2e-pass-123"}}}')"
# 保存后 needs_setup 应为 false
NEEDS=$("$PY" -c "import json;d=json.load(open('$LOGS/last_body.json'));print('1' if not d['state']['needs_setup'] else '0')")
chk "保存后 needs_setup=false" 1 "$NEEDS"
# 密码哈希落库（pbkdf2，非明文）
PW_FMT="$(dbkv "$DB_APP" admin.password)"
chk "密码哈希落库" 1 "$("$PY" -c "import sys;print(1 if sys.argv[1].startswith('pbkdf2_sha256\$') else 0)" "$PW_FMT")"
# 秘密打码保留：MASKED/留空不覆盖原值（用 DB 直查断言）
chk "打码保存 200" 200 "$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/config -H 'Content-Type: application/json' -d '{"values":{"phone":{"token":"__MASKED__"},"admin":{"password":""}}}')"
PW_KEPT="$(dbkv "$DB_APP" admin.password)"
chk "秘密留空保留原值" 1 "$([ "$PW_KEPT" = "$PW_FMT" ] && echo 1 || echo 0)"
# 未登录访问 API → 401
chk "未登录 401" 401 "$(code $AB/admin/api/status)"
# 新密码登录
chk "新密码登录 200" 200 "$(code -c "$LOGS/ck.txt" -X POST $AB/admin/login -H 'Content-Type: application/json' -d '{"password":"e2e-pass-123"}')"
# 测试发送（走完整链路到 mock）
chk "测试发送 200" 200 "$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/test -H 'Content-Type: application/json' -d '{"title":"e2e"}')"
# 最近消息：测试发送应已入库；改 recent_limit=3 后落库生效
ST="$(code -b "$LOGS/ck.txt" $AB/admin/api/status)"
RC=$("$PY" -c "import json;print(json.load(open('$LOGS/last_body.json'))['recent_count'])")
chk "recent 计数入库" 1 "$([ "$RC" -ge 1 ] && echo 1 || echo 0)"
chk "改 recent_limit 200" 200 "$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/config -H 'Content-Type: application/json' -d '{"values":{"storage":{"recent_limit":"3"}}}')"
RL="$(dbkv "$DB_APP" storage.recent_limit)"
chk "recent_limit 落库" 1 "$([ "$RL" = "3" ] && echo 1 || echo 0)"
# 改端口 → needs_restart 提示
chk "改端口提示重启 200" 200 "$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/config -H 'Content-Type: application/json' -d '{"values":{"server":{"port":18789}}}')"
NR=$("$PY" -c "import json;d=json.load(open('$LOGS/last_body.json'));print('1' if d['needs_restart'] else '0')")
chk "needs_restart=true" 1 "$NR"
# 重启（os.execve 自替换）→ 新端口 health
RS="$(code -b "$LOGS/ck.txt" -X POST $AB/admin/api/restart)"
if [ "$RS" = "200" ] || [ "$RS" = "000" ]; then pass=$((pass+1)); echo "PASS 重启已触发 ($RS)"; else fail=$((fail+1)); echo "FAIL 重启已触发 (期望200|000 实际$RS)"; fi
for i in $(seq 1 100); do curl -sf http://127.0.0.1:18789/health >/dev/null 2>&1 && break; sleep 0.3; done
chk "重启后新端口 health 200" 200 "$(code http://127.0.0.1:18789/health)"
# 重启后会话仍有效（secret 持久化在 DB）
chk "重启后会话保持 200" 200 "$(code -b "$LOGS/ck.txt" http://127.0.0.1:18789/admin/api/status)"
# 开机自启状态查询（有 manage.py 即 available，不改动注册表）
AS="000"
for i in 1 2 3; do AS="$(code -b "$LOGS/ck.txt" http://127.0.0.1:18789/admin/api/autostart)"; [ "$AS" = "200" ] && break; sleep 1; done
chk "自启状态 200" 200 "$AS"
kill $RELAY3_PID 2>/dev/null; wait $RELAY3_PID 2>/dev/null; RELAY3_PID=""

# 6) 摘要与关键证据
echo "----"
echo "响应体示例: $(head -c 200 "$LOGS/last_body.json")"
echo "mock收到500次数: $(grep -c '人为返回 500' $MOCK_LOG)"
echo "mock送达日志末行: $(grep '200 收到' $MOCK_LOG | tail -1)"
echo "PASS=$pass FAIL=$fail"
