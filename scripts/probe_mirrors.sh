#!/bin/bash
# 探测下载源连通性
echo "== tsinghua Adoptium JDK17 =="
curl -sI -m 8 https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/ | head -1
curl -s -m 8 https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/ | grep -o 'OpenJDK17U-jdk_x64_linux_hotspot_[^"]*\.tar\.gz' | sort -uV | tail -2
echo "== tencent gradle =="
curl -sI -m 8 https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip | head -1
echo "== google cmdline-tools =="
curl -sI -m 8 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip | head -1
echo "== tencent cmdline-tools =="
curl -sI -m 8 https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip | head -1
