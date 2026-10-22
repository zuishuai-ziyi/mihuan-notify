"""admin.py — relay-server 管理与通道 API（/admin 前缀）。

会话认证：HMAC 签名 cookie（与 pc-relay 同方案）。
通道模型：PC 用 push_token 推送，手机用 poll_token 拉取（拉取支持长轮询）。
"""

import base64
import functools
import hashlib
import hmac
import json
import logging
import secrets
import store as store_mod
import time

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

log = logging.getLogger("relay-server.admin")

router = APIRouter(prefix="/admin")

# 由 main.py 在 include_router 前注入（避免 __main__ 循环导入）
relay_main = None


def bind(relay) -> None:
    global relay_main
    relay_main = relay


COOKIE = "mihuan_relay"
TTL = 12 * 3600
MAX_TITLE = 64
MAX_BODY_CHARS = 4096


# ---------------------------------------------------------------- 会话（HMAC cookie，同 pc-relay 方案）

def _sign(secret: bytes, payload: bytes) -> str:
    return hmac.new(secret, payload, hashlib.sha256).hexdigest()


def make_token(secret: bytes) -> str:
    payload = json.dumps({"ts": time.time(), "rand": secrets.token_hex(8)}).encode()
    b64 = base64.urlsafe_b64encode(payload).decode().rstrip("=")
    return f"{b64}.{_sign(secret, payload)}"


def check_token(secret: bytes, token: str) -> bool:
    try:
        b64, sig = token.rsplit(".", 1)
        pad = "=" * (-len(b64) % 4)
        payload = base64.urlsafe_b64decode(b64 + pad)
        if not hmac.compare_digest(_sign(secret, payload), sig):
            return False
        data = json.loads(payload)
        return time.time() - float(data["ts"]) <= TTL
    except Exception:
        return False


def _set_cookie(resp: JSONResponse, token: str) -> None:
    resp.set_cookie(COOKIE, token, max_age=TTL, httponly=True, samesite="lax")


def authed(request: Request) -> bool:
    C = relay_main.C
    tok = request.cookies.get(COOKIE)
    return bool(tok and check_token(C.admin_secret, tok))


def guard(fn):
    """未登录返回 401 的装饰器。"""
    @functools.wraps(fn)
    async def wrapper(*args, **kwargs):
        request: Request = kwargs.get("request") or next(
            (a for a in args if isinstance(a, Request)), None)
        if request is None or not authed(request):
            return JSONResponse({"ok": False, "error": "unauthorized"}, status_code=401)
        return await fn(*args, **kwargs)
    return wrapper


# ---------------------------------------------------------------- 登录 / 登出

@router.post("/login")
async def login(request: Request):
    C = relay_main.C
    try:
        data = await request.json()
    except Exception:
        data = {}
    pw = str(data.get("password", ""))
    if C.needs_setup:
        # 未设管理密码：免密进入（首次设置向导）
        resp = JSONResponse({"ok": True, "setup": True})
        _set_cookie(resp, make_token(C.admin_secret))
        return resp
    if pw and store_mod.verify_password(pw, C.admin_password_hash):
        resp = JSONResponse({"ok": True})
        _set_cookie(resp, make_token(C.admin_secret))
        return resp
    time.sleep(0.5)
    return JSONResponse({"ok": False, "error": "bad_credentials"}, status_code=401)


@router.post("/logout")
async def logout():
    resp = JSONResponse({"ok": True})
    resp.delete_cookie(COOKIE)
    return resp


# ---------------------------------------------------------------- 状态

@router.get("/api/status")
@guard
async def api_status(request: Request):
    C = relay_main.C
    st = C.stats()
    chans = C.list_channels()
    return {
        "ok": True,
        "service": "relay-server",
        "version": store_mod.VERSION,
        "uptime_sec": int(time.time() - relay_main.STARTED_AT),
        "stats": st,
        "channels_online": sum(1 for c in chans if c["online"]),
        "channels_total": len(chans),
        "docker": relay_main.IN_DOCKER,
        "listen": f"{C.host}:{C.port}",
        "state": C.describe(),
    }


# ---------------------------------------------------------------- 通道管理

@router.get("/api/channels")
@guard
async def api_channels(request: Request):
    return {"ok": True, "channels": relay_main.C.list_channels()}


@router.post("/api/channels")
@guard
async def api_channel_create(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    name = str(data.get("name", "")).strip()
    if not name:
        return JSONResponse({"ok": False, "error": "name_required"}, status_code=400)
    if len(name) > 64:
        return JSONResponse({"ok": False, "error": "name_too_long"}, status_code=400)
    ch = relay_main.C.create_channel(name)
    return {"ok": True, "channel": ch}


@router.patch("/api/channels/{cid}")
@guard
async def api_channel_rename(cid: str, request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    name = str(data.get("name", "")).strip()
    if not name or len(name) > 64:
        return JSONResponse({"ok": False, "error": "invalid_name"}, status_code=400)
    if not relay_main.C.get_channel(cid):
        return JSONResponse({"ok": False, "error": "not_found"}, status_code=404)
    relay_main.C.rename_channel(cid, name)
    return {"ok": True}


@router.delete("/api/channels/{cid}")
@guard
async def api_channel_delete(cid: str, request: Request):
    if not relay_main.C.delete_channel(cid):
        return JSONResponse({"ok": False, "error": "not_found"}, status_code=404)
    return {"ok": True}


@router.post("/api/channels/{cid}/rotate/{which}")
@guard
async def api_channel_rotate(cid: str, which: str, request: Request):
    tok = relay_main.C.rotate_token(cid, which)
    if tok is None:
        return JSONResponse({"ok": False, "error": "invalid_channel_or_kind"}, status_code=404)
    return {"ok": True, "token": tok}


# ---------------------------------------------------------------- 最近消息

@router.get("/api/recent")
@guard
async def api_recent(request: Request, channel: str | None = None, limit: int = 200):
    return {"ok": True, "messages": relay_main.C.get_recent(channel, limit)}


@router.post("/api/recent/clear")
@guard
async def api_recent_clear(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    relay_main.C.clear_messages(data.get("channel_id") or None)
    return {"ok": True}


# ---------------------------------------------------------------- 测试发送（Web UI → 通道 → 手机）

@router.post("/api/test")
@guard
async def api_test(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    cid = str(data.get("channel_id", ""))
    if not relay_main.C.get_channel(cid):
        return JSONResponse({"ok": False, "error": "channel_not_found"}, status_code=404)
    env = {
        "v": 1,
        "id": f"test-{secrets.token_hex(6)}",
        "ts": store_mod._now_iso(),
        "title": str(data.get("title", "中继测试"))[:MAX_TITLE],
        "body": str(data.get("body", "这是一条来自中继 Web UI 的测试消息"))[:MAX_BODY_CHARS],
        "priority": "normal",
        "source": "webui-test",
    }
    relay_main.C.enqueue(cid, env)
    return {"ok": True, "id": env["id"]}


# ---------------------------------------------------------------- 配置

CONFIG_SCHEMA = {
    "server": {
        "label": "服务",
        "keys": {
            "server.host": {"label": "监听地址", "type": "string",
                            "help": "Docker 部署时由环境变量 RELAY_HOST/RELAY_PORT 决定，此处修改无效"},
        },
    },
    "storage": {
        "label": "存储",
        "keys": {
            "storage.recent_limit": {"label": "每通道消息保留条数", "type": "int"},
            "relay.max_wait": {"label": "长轮询上限（秒）", "type": "float",
                               "help": "手机端拉取挂起的最大时长，1-60"},
        },
    },
    "admin": {
        "label": "管理",
        "keys": {
            "admin.password": {"label": "管理密码", "type": "secret",
                               "help": "留空未设置时进入免密设置模式；保存明文自动哈希"},
        },
    },
}


@router.get("/api/config")
@guard
async def api_config_get(request: Request):
    C = relay_main.C
    values = {
        "server.host": C.get("server.host"),
        "storage.recent_limit": C.get("storage.recent_limit"),
        "relay.max_wait": C.get("relay.max_wait"),
        "admin.password": "__MASKED__" if C.admin_password_hash else "",
    }
    hints = {"admin.password_hint": ("(已设置)" if C.admin_password_hash else "(未设置)")}
    return {"ok": True, "schema": CONFIG_SCHEMA, "values": values,
            "hints": hints, "state": C.describe()}


@router.post("/api/config")
@guard
async def api_config_set(request: Request):
    C = relay_main.C
    try:
        data = await request.json()
    except Exception:
        data = {}
    values = data.get("values", {})
    notes: list[str] = []

    updates: dict[str, str] = {}

    host = values.get("server.host")
    if isinstance(host, str) and host.strip():
        updates["server.host"] = host.strip()

    def _int(key, lo, hi):
        v = values.get(key)
        if v in (None, "") or (isinstance(v, str) and v.strip() == ""):
            return
        try:
            n = int(v)
        except (TypeError, ValueError):
            raise ValueError(f"{key} 不是整数")
        if not (lo <= n <= hi):
            raise ValueError(f"{key} 超出范围 {lo}-{hi}")
        updates[key] = str(n)

    def _float(key, lo, hi):
        v = values.get(key)
        if v in (None, "") or (isinstance(v, str) and v.strip() == ""):
            return
        try:
            f = float(v)
        except (TypeError, ValueError):
            raise ValueError(f"{key} 不是数字")
        if not (lo <= f <= hi):
            raise ValueError(f"{key} 超出范围 {lo}-{hi}")
        updates[key] = str(f)

    try:
        _int("storage.recent_limit", 1, 100000)
        _float("relay.max_wait", 1.0, 60.0)
    except ValueError as e:
        return JSONResponse({"ok": False, "error": str(e)}, status_code=400)

    pw = values.get("admin.password")
    if isinstance(pw, str) and pw and "__MASKED__" not in pw and "••" not in pw:
        if len(pw) < 6:
            return JSONResponse({"ok": False, "error": "密码至少 6 位"}, status_code=400)
        updates["admin.password"] = store_mod.hash_password(pw)
        notes.append("管理密码已更新")

    if updates:
        C.set_many(updates)
        C.reload()

    return {"ok": True, "notes": notes, "needs_restart": False, "state": C.describe()}
