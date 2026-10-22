#!/bin/bash
set -e
SDK=$HOME/android-sdk
cd ~/downloads
python3 -m zipfile -e ptools.zip $SDK
chmod +x $SDK/platform-tools/adb 2>/dev/null || true
echo "=== 最终验证 ==="
du -sh $SDK/platform-tools
test -x $SDK/platform-tools/adb && echo "adb ✓"
for f in aapt2 apksigner d8 zipalign; do test -f $SDK/build-tools/34.0.0/$f && echo "$f ✓" || echo "$f ✗"; done
echo "android.jar: $(du -h $SDK/platforms/android-34/android.jar | cut -f1)"
grep -E "ApiLevel" $SDK/platforms/android-34/source.properties
echo OK
