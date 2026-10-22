#!/bin/bash
# 搭建 Android 构建环境（无 sudo）：JDK17 + Gradle 8.7 + Android cmdline-tools
set -e
mkdir -p ~/downloads && cd ~/downloads

echo "=== 1) 并行下载（清华 JDK / 腾讯 gradle / 腾讯 cmdtools）==="
curl -sS -f -o jdk17.tar.gz https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz &
J=$!
curl -sS -f -o gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip &
G=$!
curl -sS -f -o cmdtools.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip &
C=$!
wait $J $G $C
ls -la jdk17.tar.gz gradle.zip cmdtools.zip

echo "=== 2) 解压 ==="
mkdir -p ~/jdk17 ~/gradle ~/android-sdk/cmdline-tools
tar xzf jdk17.tar.gz -C ~/jdk17 --strip-components=1

python3 -m zipfile -e gradle.zip ~/gradle_tmp
mv ~/gradle_tmp/gradle-8.7/* ~/gradle/
rmdir ~/gradle_tmp/gradle-8.7 ~/gradle_tmp
chmod +x ~/gradle/bin/gradle

python3 -m zipfile -e cmdtools.zip ~/ct_tmp
mv ~/ct_tmp/cmdline-tools ~/android-sdk/cmdline-tools/latest
rmdir ~/ct_tmp
find ~/android-sdk/cmdline-tools/latest/bin -type f -exec chmod +x {} +

echo "=== 3) 验证 ==="
~/jdk17/bin/java -version 2>&1 | head -1
JAVA_HOME=~/jdk17 ~/gradle/bin/gradle --version 2>/dev/null | grep -E "Gradle|JVM:" | head -3
echo "=== 完成 ==="
