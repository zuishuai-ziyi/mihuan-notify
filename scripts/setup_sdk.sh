#!/bin/bash
# 安装 Android SDK 组件
set -e
export JAVA_HOME=$HOME/jdk17
export ANDROID_HOME=$HOME/android-sdk
SDKM=$HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager

echo "=== 1) 接受许可证 ==="
yes | $SDKM --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1 || true

echo "=== 2) 安装 platform-tools / platforms;android-34 / build-tools;34.0.0 ==="
$SDKM --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | tail -3

echo "=== 3) 验证 ==="
ls $ANDROID_HOME
ls $ANDROID_HOME/platforms $ANDROID_HOME/build-tools
echo "=== 完成 ==="
