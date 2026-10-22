"""main.py — pc-relay 应用入口：/webhook /health，转发与重试。

应用化（v2.1）：
- 配置+消息存 SQLite（store.Store 单例，全局 C）：kv 键值表 + messages 表
- Web 界面保存后立即热生效；host/port 变更 → pending_restart → /admin/api/restart
- 管理密码存哈希（pbkdf2）；admin_secret 持久化，重启后会话保持
- 旧 config.toml 不再读取（PC_RELAY_DB 指定数据库路径，默认 relay.db）

启动：python main.py（或 uvicorn main:app）。
"""

import asyncio
import hmac
import json
import logging
import os
import secrets
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

import store as store_mod
import admin

MAX_BODY_BYTES = 64 * 1024  # 请求体上限，超限 413
MAX_TITLE = 64              # 通知标题截断长度
MAX_BODY_CHARS = 4096       # 通知正文截断长度
RETRY_DELAYS = [1.0, 3.0]   # 重试间隔（第2、3次尝试前），更后续重试复用最后一个

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pc-relay")

VERSION = "2.2.3"

C = store_mod.load_runtime()          # 全局存储+配置单例（admin.py 经 bind 引用）
STARTED_AT = time.time()

app = FastAPI(title="pc-relay", docs_url=None, redoc_url=None)


def new_id() -> str:
    return str(uuid.uuid4())


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def err(status: int, error: str, **extra) -> JSONResponse:
    return JSONResponse({"ok": False, "error": error, **extra}, status_code=status)


def check_bearer(request: Request) -> bool:
    """恒定时间校验 Authorization: Bearer <token>。"""
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        return False
    return hmac.compare_digest(auth[7:].strip().encode(), C.webhook_token.encode())


@app.get("/health")
async def health():
    return {"ok": True, "service": "pc-relay", "version": VERSION, "needs_setup": C.needs_setup}


@app.post("/webhook")
async def webhook(request: Request):
    if not check_bearer(request):
        return err(401, "unauthorized")

    raw = await request.body()
    if len(raw) > MAX_BODY_BYTES:
        return err(413, "too_large")

    ctype = request.headers.get("content-type", "").split(";")[0].strip().lower()
    q = request.query_params
    title = body = source = None
    priority = "normal"

    if ctype == "application/json":
        try:
            data = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return err(400, "invalid_json")
        if not isinstance(data, dict):
            return err(400, "invalid_json")
        title, body, source = data.get("title"), data.get("body"), data.get("source")
        priority = data.get("priority", "normal")
        if not isinstance(title, str) or not isinstance(body, str):
            return err(400, "title_and_body_required")
        if source is not None and not isinstance(source, str):
            return err(400, "invalid_source")
        if priority not in ("normal", "high"):
            return err(400, "invalid_priority")
    else:
        # 查询参数模式（?title=&body=）或纯文本模式
        text = raw.decode("utf-8", errors="replace").strip() if raw else ""
        if "title" in q or "body" in q or "source" in q:
            title = q.get("title") or "Webhook"
            body = q.get("body", "")
            source = q.get("source")
            priority = q.get("priority", "normal")
        elif text:
            title, body = "Webhook", text
        else:
            return err(400, "empty_message")
        if priority not in ("normal", "high"):
            return err(400, "invalid_priority")

    envelope = {
        "v": 1,
        "id": new_id(),
        "ts": now_iso(),
        "title": title[:MAX_TITLE],
        "body": body[:MAX_BODY_CHARS],
        "priority": priority,
    }
    if source:
        envelope["source"] = source[:64]

    delivered, attempts = await forward(envelope)
    if delivered:
        log.info("webhook ok id=%s source=%s attempts=%s title=%r", envelope["id"], source, attempts, envelope["title"])
        admin.record(envelope, True, attempts)
        return {"ok": True, "id": envelope["id"], "delivered": True}
    log.warning("webhook forward FAILED id=%s attempts=%s", envelope["id"], attempts)
    admin.record(envelope, False, attempts)
    return err(502, "phone_unreachable", attempts=attempts)


async def forward(envelope: dict) -> tuple[bool, int]:
    """按 C.relay_mode 转发信封：direct=直连手机 /notify；relay=经中继服务器。
    4xx 不重试（重试无意义），5xx/网络错误重试。每次实时读 C.*：配置热生效。"""
    if C.relay_mode == "relay":
        return await forward_relay(envelope)
    return await forward_direct(envelope)


async def forward_direct(envelope: dict) -> tuple[bool, int]:
    """直连模式：POST {phone_url}/notify，头 X-Auth-Token=phone_token。"""
    if not C.phone_url or not C.phone_token:
        return False, 0
    headers = {"X-Auth-Token": C.phone_token}
    attempts = 0
    async with httpx.AsyncClient(timeout=C.phone_timeout) as client:
        while attempts < C.phone_retries:
            attempts += 1
            try:
                r = await client.post(f"{C.phone_url}/notify", json=envelope, headers=headers)
                if r.status_code == 200:
                    return True, attempts
                log.warning("phone HTTP %s (attempt %s): %s", r.status_code, attempts, r.text[:200])
                if 400 <= r.status_code < 500:
                    return False, attempts
            except (httpx.HTTPError, OSError) as e:
                log.warning("phone unreachable (attempt %s): %s", attempts, type(e).__name__)
            if attempts < C.phone_retries:
                await asyncio.sleep(RETRY_DELAYS[min(attempts - 1, len(RETRY_DELAYS) - 1)])
    return False, attempts


async def forward_relay(envelope: dict) -> tuple[bool, int]:
    """中继模式：POST {relay_url}/channels/{cid}/messages，头 X-Push-Token。
    中继入库成功即视为「已送达中继」（手机在线时数秒内拉走）；
    连接错误/5xx 重试，4xx 不重试。"""
    if not (C.relay_url and C.relay_push_token and C.relay_channel_id):
        return False, 0
    url = f"{C.relay_url}/channels/{C.relay_channel_id}/messages"
    headers = {"X-Push-Token": C.relay_push_token}
    attempts = 0
    async with httpx.AsyncClient(timeout=C.phone_timeout) as client:
        while attempts < C.phone_retries:
            attempts += 1
            try:
                r = await client.post(url, json=envelope, headers=headers)
                if r.status_code == 200:
                    data = r.json()
                    if data.get("accepted", 0) >= 1:
                        return True, attempts
                    # accepted=0 且非 duplicate：中继拒收（如超限）→ 视为失败但不重试
                    if data.get("duplicate", 0):
                        return True, attempts
                    log.warning("relay rejected: %s", data)
                    return False, attempts
                log.warning("relay HTTP %s (attempt %s): %s", r.status_code, attempts, r.text[:200])
                if 400 <= r.status_code < 500:
                    return False, attempts
            except (httpx.HTTPError, OSError) as e:
                log.warning("relay unreachable (attempt %s): %s", attempts, type(e).__name__)
            if attempts < C.phone_retries:
                await asyncio.sleep(RETRY_DELAYS[min(attempts - 1, len(RETRY_DELAYS) - 1)])
    return False, attempts


# ------------------------------------------------------- Web UI 管理功能挂载
# （必须在 uvicorn.run 之前：__main__ 模式下 uvicorn.run 会阻塞，之后代码不执行）

# 管理 API（/admin/login /admin/api/*）；先注入引用再 include，避免 __main__ 循环导入
admin.bind(sys.modules[__name__])
app.include_router(admin.router)

# Web UI 静态资源（Vue 构建产物，可选：目录不存在时仅无 UI）
# frozen（PyInstaller onedir）：datas 解包在 _internal/ 下，__file__ 也在 _internal/ → 路径一致
if getattr(sys, "frozen", False):
    _WEBUI_DIST = Path(sys._MEIPASS) / "webui" / "dist"
else:
    _WEBUI_DIST = Path(__file__).resolve().parent / "webui" / "dist"
if _WEBUI_DIST.is_dir():
    app.mount("/admin/ui", StaticFiles(directory=_WEBUI_DIST, html=True), name="webui")

    @app.middleware("http")
    async def _webui_cache_headers(request, call_next):
        resp = await call_next(request)
        p = request.url.path
        if p == "/admin/ui" or p.startswith("/admin/ui/"):
            if p.endswith(".html") or p == "/admin/ui/":
                # HTML 入口禁缓存：UI 更新后浏览器必须拉新 index.html，
                # 否则启发式缓存会继续引用旧 JS，出现“新功能不出现”
                resp.headers["Cache-Control"] = "no-store"
            elif "/assets/" in p:
                # 带 content hash 的产物可长缓存
                resp.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        return resp
else:
    log.info("webui/dist 不存在，跳过 Web UI 挂载（开发时可用 npm run dev）")


if __name__ == "__main__":
    import uvicorn

    if C.created:
        log.info("=" * 60)
        log.info("首次启动：已创建数据库 %s", C.path)
        log.info("浏览器打开 http://127.0.0.1:%s/admin/ui/ 完成首次配置", C.port)
        log.info("=" * 60)

    # Windows 重启链路：/api/restart 会先拉起分离式子进程再退出父进程。
    # 子进程可能比父进程的端口释放更早 bind（EADDRINUSE），此处自动重试。
    if sys.platform == "win32":
        import socket as _socket
        _deadline = time.time() + 30
        while True:
            try:
                uvicorn.run(app, host=C.host, port=C.port, log_level="info")
                break
            except OSError as e:
                if getattr(e, "winerror", None) != 10048 or time.time() > _deadline:
                    raise
                log.warning("端口 %s 被占用（旧进程尚未退出），3 秒后重试…", C.port)
                time.sleep(3.0)
    else:
        uvicorn.run(app, host=C.host, port=C.port, log_level="info")
