# -*- mode: python ; coding: utf-8 -*-
# pc-relay PyInstaller 打包配置（onedir）。
# 构建（Windows 侧）：python.exe -m PyInstaller pc-relay.spec --noconfirm
# 产物：build/pyinstaller/dist/pc-relay/pc-relay.exe（+ 内含 _internal/ 与 webui 静态资源）

import os
from pathlib import Path

ROOT = Path(SPECPATH)          # Phone/ 项目根
PCR = ROOT / "pc-relay"         # pc-relay 源码目录
WEBUI_DIST = PCR / "webui" / "dist"

datas = []
if WEBUI_DIST.is_dir():
    datas.append((str(WEBUI_DIST), "webui/dist"))
else:
    raise SystemExit("webui/dist 不存在——先在 pc-relay/webui 下 npm run build")

a = Analysis(
    [str(PCR / "main.py")],
    pathex=[str(PCR)],
    binaries=[],
    datas=datas,
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=["PIL", "Pillow", "tkinter", "test", "unittest"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="pc-relay",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,        # 保留控制台：日志可见，manage.py 自启亦依赖
    disable_windowed_traceback=False,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="pc-relay",
)
