#!/bin/bash
set -e
SDK=$HOME/android-sdk
cd ~/downloads
# p34 尚未移动，bt 已移过（重跑验证）
mv ~/p34_tmp/android-34 $SDK/platforms/android-34
rm -rf ~/p34_tmp
chmod +x $SDK/build-tools/34.0.0/* $SDK/platform-tools/adb $SDK/platform-tools/fastboot 2>/dev/null || true

echo "=== 验证 ==="
du -sh $SDK/build-tools/34.0.0 $SDK/platforms/android-34 $SDK/platform-tools
for f in aapt2 apksigner d8 zipalign; do test -f $SDK/build-tools/34.0.0/$f && echo "$f ✓" || echo "$f ✗"; done
test -f $SDK/platforms/android-34/android.jar && echo "android.jar ✓ ($(du -h $SDK/platforms/android-34/android.jar | cut -f1))"
grep -E "ApiLevel" $SDK/platforms/android-34/source.properties
echo "=== 完成 ==="
