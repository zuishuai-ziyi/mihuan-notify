#!/bin/bash
# 等待 127.0.0.1:8787/health 就绪并查看自启日志
cd "$(dirname "$0")/.." || exit 1
for i in $(seq 1 50); do
  if curl -sS -m 2 http://127.0.0.1:8787/health 2>/dev/null | grep -q '"ok":true'; then
    echo "✓ 服务已被 vbs 拉起（约 ${i}s）"
    echo "--- autostart.log 末尾 ---"
    tail -3 deploy/autostart.log 2>/dev/null
    exit 0
  fi
  sleep 1
done
echo "✗ 50s 内未就绪"
tail -5 deploy/autostart.log 2>/dev/null
exit 1
