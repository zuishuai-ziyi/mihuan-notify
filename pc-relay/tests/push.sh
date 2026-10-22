#!/bin/bash
# 手动推送一条消息到手机：push.sh [标题] [正文] [priority]
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
read -r PHONE_URL TOKEN < <("$DIR/.venv/bin/python" - "$DIR" <<'EOF'
import os, sys, tomllib
with open(os.path.join(sys.argv[1], 'config.toml'), 'rb') as f:
    c = tomllib.load(f)
print(c['phone']['url'], c['phone']['token'])
EOF
)
TITLE="${1:-测试消息}"
BODY="${2:-来自 PC 端的推送}"
PRIO="${3:-high}"
ID=$(cat /proc/sys/kernel/random/uuid)
TS=$(date -Iseconds)
curl -sS -m 8 -X POST "$PHONE_URL/notify" \
  -H "X-Auth-Token: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"v\":1,\"id\":\"$ID\",\"ts\":\"$TS\",\"title\":\"$TITLE\",\"body\":\"$BODY\",\"source\":\"pc-relay\",\"priority\":\"$PRIO\"}"
echo
