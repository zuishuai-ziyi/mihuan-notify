#!/bin/bash
# task-6 端到端全链路验证：第三方 WebHook → PC relay → 手机 App →（人工确认）→ 手环
# 自包含：脚本内自起 PC relay，跑完自清理。用法：bash e2e_full.sh
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$DIR/tests/logs"
mkdir -p "$LOGS"
PY="$DIR/.venv/bin/python"

# 从 config.toml 读 token/地址
read -r WEBHOOK_TOKEN PHONE_URL SERVER_HOST SERVER_PORT < <("$PY" - "$DIR" <<'EOF'
import os, sys, tomllib
with open(os.path.join(sys.argv[1], 'config.toml'), 'rb') as f:
    c = tomllib.load(f)
print(c['webhook']['token'], c['phone']['url'], c['server'].get('host', '0.0.0.0'), c['server'].get('port', 8787))
EOF
)
BASE="http://127.0.0.1:$SERVER_PORT"
echo "== 配置：relay=$BASE phone=$PHONE_URL =="

# 1. 启动 PC relay（后台，本脚本内存活；uvicorn 需在 app 目录下才能 import main）
export PC_RELAY_CONFIG="$DIR/config.toml"
cd "$DIR"
"$PY" -m uvicorn main:app --host "$SERVER_HOST" --port "$SERVER_PORT" > "$LOGS/e2e_full_relay.log" 2>&1 &
RELAY_PID=$!
trap 'kill $RELAY_PID 2>/dev/null' EXIT

# 2. 等就绪（/mnt/c 冷启动可达 10s+，最多等 30s）
echo "== 等待 relay 就绪 =="
ok=0
for i in $(seq 1 100); do
  if curl -sS -m 2 "$BASE/health" 2>/dev/null | grep -q '"ok":true'; then ok=1; break; fi
  sleep 0.3
done
[ "$ok" = 1 ] && echo "relay 就绪（$((i/10)).$((i%10))s 左右）" || { echo "❌ relay 未就绪"; tail -5 "$LOGS/e2e_full_relay.log"; exit 1; }

# 3. 三种接入模式各发一条（全走真实 webhook → 手机链路）
pass=0; fail=0
check() { # $1=名称 $2=期望包含 $3=响应
  if echo "$3" | grep -q "$2"; then echo "✓ $1: $3"; pass=$((pass+1)); else echo "✗ $1: $3"; fail=$((fail+1)); fi
}

R1=$(curl -sS -m 15 -X POST "$BASE/webhook" \
  -H "Authorization: Bearer $WEBHOOK_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"E2E #1 JSON","body":"端到端第一条（JSON 模式），应出现在手机和手环","source":"e2e","priority":"high"}')
check "1/3 JSON 模式" '"delivered":true' "$R1"

R2=$(curl -sS -m 15 -X POST "$BASE/webhook" \
  -H "Authorization: Bearer $WEBHOOK_TOKEN" -H "Content-Type: text/plain" \
  --data-binary "端到端第二条（纯文本模式），正文即这条话。")
check "2/3 纯文本模式" '"delivered":true' "$R2"

R3=$(curl -sS -m 15 -X POST "$BASE/webhook?title=E2E%20%233%20%E6%9F%A5%E8%AF%A2%E5%8F%82%E6%95%B0&body=%E7%AB%AF%E5%88%B0%E7%AB%AF%E7%AC%AC%E4%B8%89%E6%9D%A1%EF%BC%88%E6%9F%A5%E8%AF%A2%E5%8F%82%E6%95%B0%E6%A8%A1%E5%BC%8F%EF%BC%89&source=e2e" \
  -H "Authorization: Bearer $WEBHOOK_TOKEN")
check "3/3 查询参数模式" '"delivered":true' "$R3"

# 4. 鉴权负例（不应转发）
R4=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' -X POST "$BASE/webhook" -H "Authorization: Bearer wrong" -d '{}')
if [ "$R4" = "401" ]; then echo "✓ 鉴权负例: 401"; pass=$((pass+1)); else echo "✗ 鉴权负例: $R4"; fail=$((fail+1)); fi

echo
echo "== 结果：PASS=$pass FAIL=$fail =="
[ "$fail" = 0 ] && echo "📱 3 条消息已推送到手机（$PHONE_URL）。请人工确认手环收到 3 条 E2E 通知。"
exit "$fail"
