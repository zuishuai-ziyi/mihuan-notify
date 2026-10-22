#!/bin/bash
echo "=== SDK 当前状态 ==="
ls $HOME/android-sdk 2>/dev/null
du -sh $HOME/android-sdk 2>/dev/null
echo "=== 清理残留进程 ==="
pkill -f sdkmanager 2>/dev/null && echo killed || echo "no process"
echo "=== 腾讯镜像目录中相关包文件名 ==="
curl -s -m 15 https://mirrors.cloud.tencent.com/AndroidSDK/ | grep -oE '"(platform-34[^"<>]*\.zip|build-tools_r34[^"<>]*\.zip|platform-tools[^"<>]*linux\.zip)"' | sort -u
