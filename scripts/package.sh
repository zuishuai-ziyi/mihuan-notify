#!/bin/bash
# package.sh — 打 PC 端发布包 dist/mihuan-notify-pc-<ver>-win64.zip
# 链路：npm build webui → PyInstaller onedir → zip（exe + _internal + manage.py + 说明）
# v2.1：配置存 SQLite（relay.db，首启自动创建），包内不再带 config.example.toml
# v2.2：新增中继模式（relay.* 配置段：mode/url/push_token/channel_id）
# v2.2.1：修复 Web UI 秘密字段“小眼睛”图标误显示为 <svg …> 文本（改用 EyeIcon.vue 组件）
# v2.2.2：小眼睛图标移入输入框内部（原为输入框右侧的独立按钮）
# v2.2.3：修复设置页「开机自启」开关不显示（loadAutostart 漏调用）+ 打包版自启模块按路径加载
# 依赖：Windows Python（pyinstaller/tomlkit 已装）+ Node/npm（Windows 侧）+ WSL bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="2.2.3"
NAME="mihuan-notify-pc-$VER-win64"
OUT="$ROOT/dist"
BUILD="$ROOT/build/pyinstaller"
EXE_DIR="$BUILD/dist/pc-relay"

cd "$ROOT"

# 1. Vue 前端构建
echo "==> [1/3] 构建 Web UI (Vite)…"
(cd pc-relay/webui && cmd.exe /c "npm run build" >/dev/null 2>&1)
[ -f pc-relay/webui/dist/index.html ] && echo "   ✓ webui/dist 就绪"

# 2. PyInstaller 打包（Windows python 不识别 WSL /mnt/c 路径，转 Windows 路径）
echo "==> [2/3] PyInstaller 打包 exe…"
SPEC_WIN=$(wslpath -w "$ROOT/pc-relay.spec")
DIST_WIN=$(wslpath -w "$BUILD/dist")
WORK_WIN=$(wslpath -w "$BUILD/work")
python.exe -m PyInstaller "$SPEC_WIN" --noconfirm \
    --distpath "$DIST_WIN" --workpath "$WORK_WIN" | tail -1
[ -f "$EXE_DIR/pc-relay.exe" ] && echo "   ✓ $EXE_DIR/pc-relay.exe"

# 3. 组装 zip（解压即用：exe + _internal + manage.py + 使用说明）
echo "==> [3/3] 组装发布包…"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
D="$STAGE/$NAME"
mkdir -p "$D" "$OUT"
cp -r "$EXE_DIR/." "$D/"
cp pc-relay/manage.py "$D/"
cp docs/deploy-pc-exe.md "$D/使用说明.md" 2>/dev/null || true
python3 -m zipfile -c "$OUT/$NAME.zip" "$STAGE/$NAME"

echo "✓ 打包完成: $OUT/$NAME.zip ($(du -h "$OUT/$NAME.zip" | cut -f1))"
