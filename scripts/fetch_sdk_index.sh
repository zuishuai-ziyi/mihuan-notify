#!/bin/bash
curl -s -m 30 https://mirrors.cloud.tencent.com/AndroidSDK/ -o sdk_index.html
echo "index size: $(du -h sdk_index.html | cut -f1)"
echo "=== platform-34* ==="
grep -oiE 'platform-34[a-zA-Z0-9._-]*\.zip' sdk_index.html | sort -u
echo "=== build-tools r34 ==="
grep -oiE 'build-tools_r34[a-zA-Z0-9._-]*\.zip' sdk_index.html | sort -u
echo "=== cmdline-tools 备用 ==="
grep -oiE 'commandlinetools[a-zA-Z0-9._-]*\.zip' sdk_index.html | sort -u | head -3
