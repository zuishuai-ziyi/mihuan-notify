#!/bin/bash
echo "=== build-tools/platforms 实际内容 ==="
ls -la $HOME/android-sdk/build-tools/ 2>/dev/null
ls $HOME/android-sdk/build-tools/*/ 2>/dev/null | head -5
ls $HOME/android-sdk/platforms 2>/dev/null || echo "platforms 不存在"
ls $HOME/android-sdk/.temp 2>/dev/null | head -3 || true
echo "=== 腾讯镜像列表（宽松匹配）==="
curl -s -m 15 https://mirrors.cloud.tencent.com/AndroidSDK/ -o /tmp/sdk_index.html
grep -oiE '(platform-34[a-zA-Z0-9._-]*\.zip|build-tools_r34[a-zA-Z0-9._-]*\.zip|platform-tools[a-zA-Z0-9._-]*\.zip)' /tmp/sdk_index.html | sort -u
