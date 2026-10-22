#!/bin/bash
echo "=== build-tools 完整性 ==="
du -sh $HOME/android-sdk/build-tools/34.0.0 2>/dev/null
for f in aapt2 d8 apksigner zipalign; do test -f $HOME/android-sdk/build-tools/34.0.0/$f && echo "$f ✓" || echo "$f ✗"; done
echo "=== 探测候选文件名 ==="
for name in platform-34_r02.zip platform-34-ext7_r03.zip platform-34_r03.zip platform-tools-latest-linux.zip platform-tools_r34.0.5-linux.zip; do
  code=$(curl -sI -m 10 -o /dev/null -w '%{http_code}' https://mirrors.cloud.tencent.com/AndroidSDK/$name)
  echo "$name -> $code"
done
