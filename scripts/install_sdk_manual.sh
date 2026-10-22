#!/bin/bash
# 手动安装 SDK 组件（腾讯镜像），替换 sdkmanager
set -e
cd ~/downloads
SDK=$HOME/android-sdk

echo "=== 探测 build-tools 文件名 ==="
BT=""
for name in build-tools_r34-linux.zip build-tools_r34.0.0-linux.zip build-tools_r34.0.1-linux.zip; do
  code=$(curl -sI -m 10 -o /dev/null -w '%{http_code}' https://mirrors.cloud.tencent.com/AndroidSDK/$name)
  echo "$name -> $code"
  [ "$code" = "200" ] && [ -z "$BT" ] && BT=$name
done
[ -z "$BT" ] && { echo "未找到 build-tools！"; exit 1; }

echo "=== 清理 sdkmanager 残留 ==="
rm -rf $SDK/build-tools $SDK/platforms $SDK/platform-tools $SDK/.temp
mkdir -p $SDK/build-tools $SDK/platforms

echo "=== 下载（$BT + platform-34-ext7 + platform-tools）==="
curl -sS -f -o bt34.zip https://mirrors.cloud.tencent.com/AndroidSDK/$BT &
P1=$!
curl -sS -f -o plat34.zip https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip &
P2=$!
curl -sS -f -o ptools.zip https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools-latest-linux.zip &
P3=$!
wait $P1 $P2 $P3
ls -la bt34.zip plat34.zip ptools.zip

echo "=== 解压布置 ==="
python3 -m zipfile -e bt34.zip ~/bt_tmp
mv ~/bt_tmp/android-* $SDK/build-tools/34.0.0
python3 -m zipfile -e plat34.zip ~/p34_tmp
mv ~/p34_tmp/android-34-ext7 $SDK/platforms/android-34
python3 -m zipfile -e ptools.zip $SDK
rm -rf ~/bt_tmp ~/p34_tmp
chmod +x $SDK/build-tools/34.0.0/* $SDK/platform-tools/adb $SDK/platform-tools/fastboot 2>/dev/null || true

echo "=== 验证 ==="
du -sh $SDK/build-tools/34.0.0 $SDK/platforms/android-34 $SDK/platform-tools
for f in aapt2 apksigner d8 zipalign; do test -f $SDK/build-tools/34.0.0/$f && echo "$f ✓" || echo "$f ✗"; done
test -f $SDK/platforms/android-34/android.jar && echo "android.jar ✓ ($(du -h $SDK/platforms/android-34/android.jar | cut -f1))"
grep -E "ApiLevel|Extension" $SDK/platforms/android-34/source.properties
echo "=== 完成 ==="
