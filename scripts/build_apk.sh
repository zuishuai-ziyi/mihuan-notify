#!/bin/bash
# 构建 APK：项目拷到 ext4 构建区，避免 /mnt/c 慢 I/O；构建成功后把产物拷回 android-app/dist/
# 用法：bash scripts/build_apk.sh [debug|release|all]   （默认 debug）
set -euo pipefail
export JAVA_HOME=$HOME/jdk17
export ANDROID_HOME=$HOME/android-sdk

VARIANT="${1:-debug}"
case "$VARIANT" in
  debug|release) VARIANTS=("$VARIANT") ;;
  all)           VARIANTS=(debug release) ;;
  *) echo "用法：$0 [debug|release|all]"; exit 2 ;;
esac

SRC=/mnt/c/Users/ZiYi/Desktop/tmp/mihuan/Phone/android-app
rm -rf ~/build/android-app
mkdir -p ~/build/android-app "$SRC/dist"
# 用 tar 拷贝并排除构建/构建缓存：/mnt/c 上 .gradle 内的 .lock 常在读时报 I/O error，cp -r 会直接失败
(cd "$SRC" && tar --exclude=./.gradle --exclude=./build --exclude=./local.properties \
     --exclude=./dist --exclude=./app/build -cf - .) | tar -C ~/build/android-app -xf -
echo "sdk.dir=$HOME/android-sdk" > ~/build/android-app/local.properties

cd ~/build/android-app
for v in "${VARIANTS[@]}"; do
  echo "==> 构建 $v APK…"
  # 首字母大写传给 gradle 任务名（assembleDebug / assembleRelease）
  task="assemble$(echo "${v:0:1}" | tr 'a-z' 'A-Z')${v:1}"
  ~/gradle/bin/gradle "$task" --no-daemon 2>&1 | grep -E "^e: |error:|BUILD |FAILURE" || true
  out="app/build/outputs/apk/$v/app-$v.apk"
  [ -f "$out" ] || { echo "   ✗ 未找到产物 $out"; exit 1; }
  cp "$out" "$SRC/dist/app-$v.apk"
  echo "   ✓ $out → android-app/dist/app-$v.apk ($(stat -c%s "$out") bytes)"
done
