#!/bin/bash
# install.sh — PC 端部署包安装脚本（在解压后的包目录内运行）
# 用法：bash install.sh
set -e
cd "$(dirname "$0")"

MIRROR="https://pypi.tuna.tsinghua.edu.cn/simple"

# 1. 配置文件：首次安装生成模板
if [ ! -f config.toml ]; then
    cp config.example.toml config.toml
    echo "✓ 已生成 config.toml 模板——请编辑填写 webhook_token / phone.url / phone.token"
fi

# 2. 虚拟环境（Debian/Ubuntu 系统常缺 ensurepip：先尝试标准创建，失败降级 --without-pip + get-pip）
if [ ! -x .venv/bin/python ]; then
    echo "· 创建虚拟环境…"
    python3 -m venv .venv 2>/dev/null || python3 -m venv --without-pip .venv \
        || { echo "❌ python3 -m venv 失败：Debian/Ubuntu 请先安装 sudo apt install python3-venv"; exit 1; }
fi
if ! .venv/bin/pip --version >/dev/null 2>&1; then
    echo "· venv 内无 pip，用 get-pip 兜底…"
    curl -sSLo get-pip.py https://bootstrap.pypa.io/get-pip.py
    .venv/bin/python get-pip.py -i "$MIRROR" -q && rm get-pip.py
fi

# 3. 依赖
echo "· 安装依赖…"
.venv/bin/pip install -q -r requirements.txt -i "$MIRROR"

echo ""
echo "✅ 安装完成。"
echo "   编辑配置：  nano config.toml"
echo "   手动启动：  .venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8787"
echo "   开机自启：  .venv/bin/python manage.py install-autostart   （需在 WSL 内运行）"
echo "   状态/注销： .venv/bin/python manage.py status | uninstall-autostart"
