"""main.py — mihuan-notify relay-server（消息中继）。

模型：通道（channel）= 一对 push_token（PC 推送）/ poll_token（手机拉取）。
PC webhook 处理器把信封 v1 POST 到 /channels/{cid}/messages（X-Push-Token）；
手机在轮询线程 GET /channels/{cid}/messages?wait=30（X-Poll-Token，长轮询），
拿到信封后本地弹通知，再 POST /channels/{cid}/ack。

Docker：python:3.12-slim + uvicorn，DB 放 /app/data（volume 建议挂载）。
"""

import asyncio
import logging
import os
import secrets
import sys
import time
from pathlib import Path

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

import admin
import store as store_mod
from admin import router as admin_router

VERSION = store_mod.VERSION

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("relay-server")

MAX_BODY_BYTES = 64 * 1024
MAX_TITLE = 64
MAX_BODY_CHARS = 4096
PRIORITIES = {"low", "normal", "high"}

IN_DOCKER = Path("/app/.dockerenv").exists() or os.environ.get("RELAY_FORCE_DOCKER") == "1"

app = FastAPI(title="mihuan-notify relay-server", version=VERSION)
C = store_mod.load_runtime()
STARTED_AT = time.time()

admin.bind(sys.modules[__name__])
app.include_router(admin.router)

# 每通道长轮询唤醒事件：enqueue 后 set，等待端 consume 后 clear
_poll_events: dict[str, asyncio.Event] = {}


def _event_for(cid: str) -> asyncio.Event:
    ev = _poll_events.get(cid)
    if ev is None:
        ev = asyncio.Event()
        _poll_events[cid] = ev
    return ev


def _notify_pollers(cid: str) -> None:
    ev = _poll_events.get(cid)
    if ev is not None:
        ev.set()


def _constant_compare(a: str, b: str) -> bool:
    return secrets.compare_digest(a.encode(), b.encode())


def _bearer_or_header(request: Request, header: str) -> str:
    """优先 X-Push-Token / X-Poll-Token，其次 Authorization: Bearer xxx。"""
    tok = request.headers.get(header, "")
    if tok:
        return tok.strip()
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return ""


def _sanitize_env(raw: dict) -> tuple[dict | None, str]:
    """校验并规整信封 v1。返回 (envelope, error)；error 非空则拒绝。"""
    if not isinstance(raw, dict):
        return None, "envelope must be an object"
    if raw.get("v") != 1:
        return None, "unsupported protocol version (v must be 1)"
    mid = str(raw.get("id", "")).strip()
    if not mid or len(mid) > 128:
        return None, "missing or invalid id"
    title = str(raw.get("title", "")).strip()
    if not title:
        return None, "missing title"
    body = str(raw.get("body", ""))
    priority = str(raw.get("priority", "normal")).lower()
    if priority not in PRIORITIES:
        priority = "normal"
    source = str(raw.get("source", ""))[:64] or None
    ts = str(raw.get("ts", "")) or store_mod._now_iso()
    env = {
        "v": 1,
        "id": mid[:128],
        "ts": ts,
        "title": title[:MAX_TITLE],
        "body": body[:MAX_BODY_CHARS],
        "priority": priority,
        "source": source,
    }
    return env, ""


def _find_channel_by_push(token: str) -> dict | None:
    for ch in C.list_channels():
        if _constant_compare(token, ch["push_token"]):
            return ch
    return None


def _find_channel_by_poll(token: str) -> dict | None:
    for ch in C.list_channels():
        if _constant_compare(token, ch["poll_token"]):
            return ch
    return None


# ---------------------------------------------------------------- 公开端点

@app.get("/health")
async def health():
    return {"ok": True, "service": "relay-server", "version": VERSION,
            "needs_setup": C.needs_setup}


@app.post("/channels/{cid}/messages")
async def push_messages(cid: str, request: Request):
    """PC → 中继：推送一条信封或 {"messages":[...]} 批量。幂等（同通道 msg_id 去重）。"""
    ch = C.get_channel(cid)
    if ch is None:
        return JSONResponse({"ok": False, "error": "channel_not_found"}, status_code=404)
    token = _bearer_or_header(request, "X-Push-Token")
    if not token or not _constant_compare(token, ch["push_token"]):
        return JSONResponse({"ok": False, "error": "invalid_token"}, status_code=401)

    cl = request.headers.get("content-length")
    if cl and cl.isdigit() and int(cl) > MAX_BODY_BYTES:
        return JSONResponse({"ok": False, "error": "payload too large"}, status_code=413)
    raw = await request.body()
    if len(raw) > MAX_BODY_BYTES:
        return JSONResponse({"ok": False, "error": "payload too large"}, status_code=413)
    try:
        import json as _json
        data = _json.loads(raw.decode("utf-8"))
    except Exception:
        return JSONResponse({"ok": False, "error": "invalid json"}, status_code=400)

    if isinstance(data, dict) and isinstance(data.get("messages"), list):
        batch = data["messages"]
    else:
        batch = [data]

    if len(batch) > 50:
        return JSONResponse({"ok": False, "error": "batch too large (max 50)"}, status_code=400)

    accepted, duplicate, errors = 0, 0, 0
    for item in batch:
        env, err = _sanitize_env(item if isinstance(item, dict) else {})
        if err:
            errors += 1
            log.warning("推送被拒 channel=%s err=%s", cid, err)
            continue
        if C.enqueue(cid, env):
            accepted += 1
        else:
            duplicate += 1
    if accepted:
        _notify_pollers(cid)
    return {"ok": True, "accepted": accepted, "duplicate": duplicate, "rejected": errors}


@app.get("/channels/{cid}/messages")
async def poll_messages(cid: str, request: Request):
    """手机 → 中继：长轮询拉取 pending 信封。wait≤relay.max_wait（Docker 默认 30s）。"""
    ch = C.get_channel(cid)
    if ch is None:
        return JSONResponse({"ok": False, "error": "channel_not_found"}, status_code=404)
    token = _bearer_or_header(request, "X-Poll-Token")
    if not token or not _constant_compare(token, ch["poll_token"]):
        return JSONResponse({"ok": False, "error": "invalid_token"}, status_code=401)

    try:
        limit = int(request.query_params.get("limit", "20"))
    except ValueError:
        limit = 20
    limit = max(1, min(limit, 100))
    try:
        wait = float(request.query_params.get("wait", "0"))
    except ValueError:
        wait = 0.0
    wait = min(max(0.0, wait), C.max_wait)

    msgs = C.fetch_pending(cid, limit)
    if not msgs and wait > 0:
        ev = _event_for(cid)
        deadline = time.monotonic() + wait
        while not msgs and time.monotonic() < deadline:
            ev.clear()
            try:
                await asyncio.wait_for(ev.wait(), timeout=min(1.0, deadline - time.monotonic()))
            except asyncio.TimeoutError:
                pass
            msgs = C.fetch_pending(cid, limit)

    C.touch_poll(cid)
    return {"ok": True, "messages": msgs}


@app.post("/channels/{cid}/ack")
async def ack_messages(cid: str, request: Request):
    """手机 → 中继：确认已处理 {"ids":[...]} → state=delivered。"""
    ch = C.get_channel(cid)
    if ch is None:
        return JSONResponse({"ok": False, "error": "channel_not_found"}, status_code=404)
    token = _bearer_or_header(request, "X-Poll-Token")
    if not token or not _constant_compare(token, ch["poll_token"]):
        return JSONResponse({"ok": False, "error": "invalid_token"}, status_code=401)
    try:
        raw = await request.body()
        import json as _json
        data = _json.loads(raw.decode("utf-8")) if raw else {}
    except Exception:
        return JSONResponse({"ok": False, "error": "invalid json"}, status_code=400)
    ids = data.get("ids")
    if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
        return JSONResponse({"ok": False, "error": "ids must be a list of strings"}, status_code=400)
    n = C.ack(cid, ids[:200])
    return {"ok": True, "acked": n}


@app.get("/channels/{cid}/ping")
async def channel_ping(cid: str, request: Request):
    """任意一侧 token 验证通道可用（PC /healthcheck、手机连通性测试共用）。"""
    ch = C.get_channel(cid)
    if ch is None:
        return JSONResponse({"ok": False, "error": "channel_not_found"}, status_code=404)
    push_tok = _bearer_or_header(request, "X-Push-Token")
    poll_tok = _bearer_or_header(request, "X-Poll-Token")
    ok = (push_tok and _constant_compare(push_tok, ch["push_token"])) or \
         (poll_tok and _constant_compare(poll_tok, ch["poll_token"]))
    if not ok:
        return JSONResponse({"ok": False, "error": "invalid_token"}, status_code=401)
    return {"ok": True, "service": "relay-server", "channel": ch["name"],
            "pending": C.pending_count(cid)}


# ---------------------------------------------------------------- 静态 UI 与启动

def _mount_ui() -> None:
    dist = Path(__file__).resolve().parent / "webui" / "dist"
    if dist.is_dir():
        app.mount("/admin/ui", StaticFiles(directory=dist, html=True), name="ui")

        @app.get("/admin")
        async def _redirect():
            from fastapi.responses import RedirectResponse
            return RedirectResponse("/admin/ui/")
    else:
        log.warning("WebUI 目录不存在：%s（仅提供 API）", dist)


_mount_ui()

if __name__ == "__main__":
    uvicorn.run(app, host=C.host, port=C.port, log_level="info")
