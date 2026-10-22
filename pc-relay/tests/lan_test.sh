#!/bin/bash
# task-5 局域网联调：PC(WSL) → 手机 App 直连验证
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
read -r PHONE_URL TOKEN < <("$DIR/.venv/bin/python" - "$DIR" <<'EOF'
import os, sys, tomllib
with open(os.path.join(sys.argv[1], 'config.toml'), 'rb') as f:
    c = tomllib.load(f)
print(c['phone']['url'], c['phone']['token'])
EOF
)

echo "== 1. GET /ping（探活，无鉴权）=="
ping_body=$(curl -sS -m 5 "$PHONE_URL/ping" 2>&1)
rc=$?
echo "rc=$rc body=$ping_body"
[ $rc -ne 0 ] && { echo "❌ 手机不可达（检查：同一 WiFi？App 服务已启动？路由器 AP 隔离？）"; exit 1; }

echo
echo "== 2. POST /notify 401 鉴权负例 =="
curl -sS -m 5 -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$PHONE_URL/notify" \
  -H "X-Auth-Token: wrong-token" -H "Content-Type: application/json" -d '{"v":1}'

echo
echo "== 3. POST /notify 正式测试消息 =="
ID=$(cat /proc/sys/kernel/random/uuid)
TS=$(date -Iseconds)
curl -sS -m 8 -X POST "$PHONE_URL/notify" \
  -H "X-Auth-Token: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"v\":1,\"id\":\"$ID\",\"ts\":\"$TS\",\"title\":\"联调测试\",\"body\":\"PC→手机链路 OK！这是来自 PC 端的测试消息\",\"source\":\"pc-relay\",\"priority\":\"high\"}"
echo
echo "== 4. 重发同 id（验证去重）=="
curl -sS -m 8 -X POST "$PHONE_URL/notify" \
  -H "X-Auth-Token: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"v\":1,\"id\":\"$ID\",\"ts\":\"$TS\",\"title\":\"联调测试\",\"body\":\"重复消息应返回 duplicate\",\"source\":\"pc-relay\",\"priority\":\"normal\"}"
echo
echo "== 完成 =="
