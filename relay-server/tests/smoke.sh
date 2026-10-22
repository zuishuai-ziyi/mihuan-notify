#!/usr/bin/env bash
# relay-server 冒烟测试：临时 DB 起服务 → 健康检查/登录/通道/推送/长轮询/ack/配额/幂等/配置/权限
# 用法：bash tests/smoke.sh   （需 bash + curl + python3，端口 18099 隔离）
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
PORT=18099
BASE="http://127.0.0.1:$PORT"
DB="$(mktemp -u /tmp/relay-smoke-XXXXXX).db"
PASS=0; FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ok  - $1"; }
bad()  { FAIL=$((FAIL+1)); say "  FAIL - $1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (want=$3 got=$2)"; fi; }

cleanup() { [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null; rm -f "$DB" "$DB-wal" "$DB-shm"; }
trap cleanup EXIT

say "== 1. 启动（临时 DB，端口 $PORT）=="
# PY：Python 解释器，默认 python3；需已装 fastapi/uvicorn（可设 RELAY_PYTHON 指定，如 pc-relay/.venv/bin/python）
PY="${RELAY_PYTHON:-python3}"
RELAY_DB="$DB" RELAY_PORT=$PORT RELAY_HOST=127.0.0.1 "$PY" -m uvicorn main:app --host 127.0.0.1 --port "$PORT" --log-level warning &
SRV_PID=$!

for i in $(seq 1 40); do
  curl -sf "$BASE/health" >/dev/null 2>&1 && break
  sleep 0.25
done

# ---- 1. 健康
r=$(curl -s "$BASE/health")
check "health ok"        "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')" "True"
check "health service"   "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["service"])')" "relay-server"
check "needs_setup"      "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["needs_setup"])')" "True"

# ---- 2. 未登录 401
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/status")
check "未登录 status 401" "$code" "401"

# ---- 3. 免密登录（needs_setup）
curl -s -c /tmp/relay-smoke-cookie.txt -X POST "$BASE/admin/login" -H 'Content-Type: application/json' -d '{"password":""}' >/dev/null
authed=$(curl -s -b /tmp/relay-smoke-cookie.txt "$BASE/admin/api/status" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')
check "免密登录后 status" "$authed" "True"

# ---- 4. 建通道
r=$(curl -s -b /tmp/relay-smoke-cookie.txt -X POST "$BASE/admin/api/channels" -H 'Content-Type: application/json' -d '{"name":"测试通道"}')
CID=$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["channel"]["id"])')
PUSH=$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["channel"]["push_token"])')
POLL=$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["channel"]["poll_token"])')
[ -n "$CID" ] && ok "建通道 id=$CID" || bad "建通道失败: $r"

# ---- 5. 通道列表
n=$(curl -s -b /tmp/relay-smoke-cookie.txt "$BASE/admin/api/channels" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["channels"]))')
check "通道列表=1" "$n" "1"

# ---- 6. 推送鉴权
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -d '{"v":1,"id":"x","title":"t","body":"b"}')
check "无 token 推送 401" "$code" "401"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: wrong" -d '{"v":1,"id":"x","title":"t","body":"b"}')
check "错 token 推送 401" "$code" "401"

# ---- 7. 推送（信封 + 批量 + 幂等）
r=$(curl -s -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: $PUSH" \
  -d '{"v":1,"id":"msg-1","title":"标题1","body":"内容1","source":"smoke","priority":"normal"}')
check "推送 msg-1"        "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["accepted"])')" "1"
r=$(curl -s -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: $PUSH" \
  -d '{"v":1,"id":"msg-1","title":"标题1","body":"内容1"}')
check "重复 msg-1 duplicate" "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["duplicate"])')" "1"
r=$(curl -s -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: $PUSH" \
  -d '{"messages":[{"v":1,"id":"msg-2","title":"标题2","body":"内容2"},{"v":1,"id":"msg-3","title":"标题3","body":"内容3","priority":"high"}]}')
check "批量推送 accepted=2" "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["accepted"])')" "2"

# ---- 8. 拉取
r=$(curl -s "$BASE/channels/$CID/messages?limit=10" -H "X-Poll-Token: $POLL")
n=$(echo "$r" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["messages"]))')
check "拉取 3 条" "$n" "3"

# ---- 9. ack
r=$(curl -s -X POST "$BASE/channels/$CID/ack" -H 'Content-Type: application/json' -H "X-Poll-Token: $POLL" -d '{"ids":["msg-1","msg-2"]}')
check "ack 2 条" "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["acked"])')" "2"
r=$(curl -s "$BASE/channels/$CID/messages" -H "X-Poll-Token: $POLL")
n=$(echo "$r" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["messages"]))')
check "拉取剩余 1 条" "$n" "1"
r=$(curl -s -X POST "$BASE/channels/$CID/ack" -H 'Content-Type: application/json' -H "X-Poll-Token: $POLL" -d '{"ids":["msg-3"]}')
check "ack 剩余" "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["acked"])')" "1"

# ---- 10. 长轮询：起推送后台 2s 后推，wait=5 应在 ~2s 返回
( sleep 2; curl -s -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: $PUSH" \
    -d '{"v":1,"id":"msg-lp","title":"长轮询","body":"x"}' >/dev/null ) &
LP_START=$(date +%s%N)
r=$(curl -s "$BASE/channels/$CID/messages?wait=5" -H "X-Poll-Token: $POLL")
LP_END=$(date +%s%N)
LP_MS=$(( (LP_END - LP_START) / 1000000 ))
n=$(echo "$r" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["messages"]))')
check "长轮询收到 1 条" "$n" "1"
if [ "$LP_MS" -ge 1500 ] && [ "$LP_MS" -le 4500 ]; then ok "长轮询耗时 ~2s (${LP_MS}ms)"; else bad "长轮询耗时异常 ${LP_MS}ms"; fi
curl -s -X POST "$BASE/channels/$CID/ack" -H 'Content-Type: application/json' -H "X-Poll-Token: $POLL" -d '{"ids":["msg-lp"]}' >/dev/null

# ---- 11. 长轮询空等：wait=2 无消息应 ~2s 返回空
LP_START=$(date +%s%N)
r=$(curl -s "$BASE/channels/$CID/messages?wait=2" -H "X-Poll-Token: $POLL")
LP_END=$(date +%s%N)
LP_MS=$(( (LP_END - LP_START) / 1000000 ))
n=$(echo "$r" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["messages"]))')
check "空长轮询 0 条" "$n" "0"
if [ "$LP_MS" -ge 1800 ]; then ok "空长轮询等待 ${LP_MS}ms"; else bad "空长轮询提前返回 ${LP_MS}ms"; fi

# ---- 12. 配置：设管理密码
code=$(curl -s -b /tmp/relay-smoke-cookie.txt -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/api/config" \
  -H 'Content-Type: application/json' -d '{"values":{"admin.password":"test-pass-123"}}')
check "设管理密码 200" "$code" "200"

# ---- 13. 新密码登录
curl -s -c /tmp/relay-smoke-cookie2.txt -X POST "$BASE/admin/login" -H 'Content-Type: application/json' -d '{"password":"test-pass-123"}' >/dev/null
authed=$(curl -s -b /tmp/relay-smoke-cookie2.txt "$BASE/admin/api/status" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')
check "新密码登录" "$authed" "True"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/login" -H 'Content-Type: application/json' -d '{"password":"wrong"}')
check "错密码 401" "$code" "401"

# ---- 14. recent（有 4 条记录：msg-1/2/3/lp）
n=$(curl -s -b /tmp/relay-smoke-cookie2.txt "$BASE/admin/api/recent" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["messages"]))')
check "recent=4" "$n" "4"
r=$(curl -s -b /tmp/relay-smoke-cookie2.txt "$BASE/admin/api/recent?channel=$CID&limit=2" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(len(d["messages"]))')
check "recent 过滤+limit" "$r" "2"

# ---- 15. 测试发送（入队）
r=$(curl -s -b /tmp/relay-smoke-cookie2.txt -X POST "$BASE/admin/api/test" -H 'Content-Type: application/json' -d "{\"channel_id\":\"$CID\",\"title\":\"T\",\"body\":\"B\"}")
check "webui 测试发送" "$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')" "True"

# ---- 16. token 轮换后旧 token 失效
NEWPOLL=$(curl -s -b /tmp/relay-smoke-cookie2.txt -X POST "$BASE/admin/api/channels/$CID/rotate/poll" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/channels/$CID/messages" -H "X-Poll-Token: $POLL")
check "旧 poll token 401" "$code" "401"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/channels/$CID/messages" -H "X-Poll-Token: $NEWPOLL")
check "新 poll token 200" "$code" "200"

# ---- 17. 删除通道 → 推送 404
curl -s -b /tmp/relay-smoke-cookie2.txt -X DELETE "$BASE/admin/api/channels/$CID" >/dev/null
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/channels/$CID/messages" -H 'Content-Type: application/json' -H "X-Push-Token: $PUSH" -d '{"v":1,"id":"zz","title":"t","body":"b"}')
check "删通道后推送 404" "$code" "404"

# ---- 18. 校验：坏信封拒绝
r=$(curl -s -X POST "$BASE/admin/api/test" -b /tmp/relay-smoke-cookie2.txt -H 'Content-Type: application/json' -d '{"channel_id":"nonexistent"}')
code=$(echo "$r" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("error",""))' 2>/dev/null)
check "测试发送到不存在通道报错" "$code" "channel_not_found"

say ""
say "== 结果: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
