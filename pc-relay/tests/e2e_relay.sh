#!/usr/bin/env bash
# 中继模式端到端联调：relay-server + pc-relay(relay 模式) + 模拟手机长轮询。
# 覆盖：免密向导配置中继 → webhook 入队 → 手机长轮询拉取+ack → 状态/连通性展示
#       → 错误 token（502）→ 切回直连模式回归 → 两侧 WebUI 静态资源。
# 全程用临时 DB，不动真实数据；输出紧凑 PASS/FAIL 摘要。
# 依赖：bash、curl、pc-relay/.venv（含 fastapi/uvicorn/httpx）。
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"       # pc-relay/
ROOT="$(cd "$DIR/.." && pwd)"                 # 仓库根
cd "$DIR"
PY="$DIR/.venv/bin/python"
LOGS="$DIR/tests/logs"; mkdir -p "$LOGS"

RPORT=18096; PPORT=18097; MPORT=18098
RBASE=http://127.0.0.1:$RPORT
PBASE=http://127.0.0.1:$PPORT
RDB=$(mktemp -u /tmp/relay-e2e-XXXXXX.db)
PDB=$(mktemp -u /tmp/pcrelay-e2e-XXXXXX.db)
STOP=/tmp/mock-relay-stop.$$
RLOG="$LOGS/e2e_relay_server.log"
PLOG="$LOGS/e2e_pc_relay.log"
HLOG="$LOGS/e2e_mock_phone_relay.log"
MLOG="$LOGS/e2e_mock_phone_direct.log"
pass=0; fail=0
RPID=""; PCPID=""; HPID=""; MPID=""

chk() { if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "PASS $1"; else fail=$((fail+1)); echo "FAIL $1 (期望$2 实际$3)"; fi; }
code() { curl -s -o "$LOGS/last_body.json" -w '%{http_code}' "$@"; }
# 从 last_body.json 取点分路径值：jget channel.id / jget messages.0.state
jget() {
  "$PY" - "$LOGS/last_body.json" "$1" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."):
    if not k:
        continue
    d = d[int(k)] if k.isdigit() else d[k]
print(d)
EOF
}
# 直读 PC 测试库 kv
dbkv() {
  "$PY" - "$1" "$2" <<'EOF'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
row = con.execute("SELECT value FROM kv WHERE key=?", (sys.argv[2],)).fetchone()
print(row[0] if row else "")
EOF
}
wait_up() { for _ in $(seq 1 100); do curl -sf "$1/health" >/dev/null 2>&1 && return 0; sleep 0.3; done; return 1; }
# 预检：测试端口必须空闲（否则会连到残留实例，结果不可信）
for u in "$RBASE" "$PBASE"; do
  if curl -sf -m 1 "$u/health" >/dev/null 2>&1; then
    echo "端口被占用：$u 已有服务在跑，请先清理残留进程后重试"
    exit 2
  fi
done
cleanup() {
  for p in "$RPID" "$PCPID" "$HPID" "$MPID"; do [ -n "$p" ] && kill $p 2>/dev/null; done
  wait 2>/dev/null
  rm -f "$STOP" "$RDB"* "$PDB"*
  return 0
}
trap cleanup EXIT

echo "== 中继模式 E2E（relay=$RPORT pc=$PPORT phone=$MPORT）=="

# ---- 1) relay-server ----
RELAY_DB="$RDB" RELAY_HOST=127.0.0.1 RELAY_PORT=$RPORT "$PY" -m uvicorn main:app \
  --host 127.0.0.1 --port $RPORT --app-dir "$ROOT/relay-server" --log-level warning >"$RLOG" 2>&1 & RPID=$!
wait_up "$RBASE"; chk "relay-server 启动" 1 "$(curl -sf $RBASE/health >/dev/null 2>&1 && echo 1 || echo 0)"
chk "relay 免密登录" 200 "$(code -c "$LOGS/relay_ck.txt" -X POST $RBASE/admin/login -H 'Content-Type: application/json' -d '{"password":""}')"
chk "relay 建通道" 200 "$(code -b "$LOGS/relay_ck.txt" -X POST $RBASE/admin/api/channels -H 'Content-Type: application/json' -d '{"name":"E2E中继"}')"
CID="$(jget channel.id)"; PUSH="$(jget channel.push_token)"; POLL="$(jget channel.poll_token)"
chk "通道凭据下发" 1 "$([ -n "$CID" ] && [ -n "$PUSH" ] && [ -n "$POLL" ] && echo 1 || echo 0)"

# ---- 2) pc-relay（初始直连未配置，需 setup）----
PC_RELAY_DB="$PDB" PC_RELAY_PORT=$PPORT "$PY" -m uvicorn main:app \
  --host 127.0.0.1 --port $PPORT --app-dir "$DIR" --log-level warning >"$PLOG" 2>&1 & PCPID=$!
wait_up "$PBASE"; chk "pc-relay 启动" 1 "$(curl -sf $PBASE/health >/dev/null 2>&1 && echo 1 || echo 0)"
WT="$(dbkv "$PDB" webhook.token)"
chk "webhook token 已生成" 1 "$([ -n "$WT" ] && echo 1 || echo 0)"
chk "pc 免密登录" 200 "$(code -c "$LOGS/pc_ck.txt" -X POST $PBASE/admin/login -H 'Content-Type: application/json' -d '{"password":""}')"
# 配置中继 + 管理密码（表单保存，热生效）
chk "中继配置保存" 200 "$(code -b "$LOGS/pc_ck.txt" -X POST $PBASE/admin/api/config -H 'Content-Type: application/json' \
  -d "{\"values\":{\"relay\":{\"mode\":\"relay\",\"url\":\"$RBASE\",\"push_token\":\"$PUSH\",\"channel_id\":\"$CID\"},\"admin\":{\"password\":\"e2e-pass-123\"}}}")"
curl -s $PBASE/health >"$LOGS/last_body.json"
chk "保存后 needs_setup 已清除" 0 "$(jget needs_setup | sed 's/False/0/; s/True/1/')"

# ---- 3) 模拟手机中继轮询 ----
STOP=/tmp/mock-relay-stop.$$; rm -f "$STOP"
"$PY" tests/mock_phone_relay.py "$RBASE" "$CID" "$POLL" >"$HLOG" 2>&1 & HPID=$!
sleep 1.5
chk "webhook 经中继投递 200" 200 "$(code -X POST $PBASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
  -d '{"title":"中继E2E标题","body":"经中继服务器到达","source":"e2e-relay","priority":"high"}')"
chk "响应 delivered=true" 1 "$(jget delivered | sed 's/False/0/; s/True/1/')"
sleep 3
chk "模拟手机收到通知" 1 "$(grep -q 'title=中继E2E标题' "$HLOG" && echo 1 || echo 0)"
chk "模拟手机已 ack" 1 "$(grep -q 'ack 1' "$HLOG" && echo 1 || echo 0)"
# 中继库记录：state=delivered
code -b "$LOGS/relay_ck.txt" "$RBASE/admin/api/recent?channel=$CID" >/dev/null
chk "中继库标记已送达" delivered "$(jget messages.0.state)"
# PC 状态：模式 + 连通性
code -b "$LOGS/pc_ck.txt" "$PBASE/admin/api/status" >/dev/null
chk "状态显示中继模式" relay "$(jget mode)"
chk "状态连通性 ok" 1 "$(jget phone.ok | sed 's/False/0/; s/True/1/')"
chk "状态文案含中继正常" 1 "$(jget phone.detail | grep -q '中继正常' && echo 1 || echo 0)"
echo "  状态 detail: $(jget phone.detail)"

# ---- 4) 错误 push token → 状态失败 + webhook 502 ----
code -b "$LOGS/pc_ck.txt" -X POST $PBASE/admin/api/config -H 'Content-Type: application/json' \
  -d '{"values":{"relay":{"push_token":"bad-token"}}}' >/dev/null
code -b "$LOGS/pc_ck.txt" "$PBASE/admin/api/status" >/dev/null
chk "错误 token 状态=失败" 0 "$(jget phone.ok | sed 's/False/0/; s/True/1/')"
t0=$(date +%s)
chk "错误 token webhook 502" 502 "$(code -X POST $PBASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
  -d '{"title":"坏token","body":"应失败"}')"
echo "  502 耗时 $(( $(date +%s) - t0 ))s（relay 返 401 属 4xx，按设计不重试）"
# 恢复
code -b "$LOGS/pc_ck.txt" -X POST $PBASE/admin/api/config -H 'Content-Type: application/json' \
  -d "{\"values\":{\"relay\":{\"push_token\":\"$PUSH\"}}}" >/dev/null

# ---- 5) 切回直连模式（回归）----
kill $HPID 2>/dev/null; HPID=""
MOCK_TOKEN=test-phone-token "$PY" tests/mock_phone.py $MPORT >"$MLOG" 2>&1 & MPID=$!
sleep 1
chk "切回直连配置保存" 200 "$(code -b "$LOGS/pc_ck.txt" -X POST $PBASE/admin/api/config -H 'Content-Type: application/json' \
  -d "{\"values\":{\"relay\":{\"mode\":\"direct\"},\"phone\":{\"url\":\"http://127.0.0.1:$MPORT\",\"token\":\"test-phone-token\"}}}")"
code -b "$LOGS/pc_ck.txt" "$PBASE/admin/api/status" >/dev/null
chk "状态回到直连模式" direct "$(jget mode)"
chk "直连 webhook 200" 200 "$(code -X POST $PBASE/webhook -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
  -d '{"title":"直连回归","body":"回归用例","source":"e2e-direct"}')"
sleep 0.5
chk "mock 手机收到直连消息" 1 "$(grep -q '直连回归' "$MLOG" && echo 1 || echo 0)"

# ---- 6) WebUI 静态资源 ----
chk "pc WebUI 可达" 200 "$(curl -s -o /dev/null -w '%{http_code}' $PBASE/admin/ui/)"
chk "relay WebUI 可达" 200 "$(curl -s -o /dev/null -w '%{http_code}' $RBASE/admin/ui/)"

echo "----"
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
