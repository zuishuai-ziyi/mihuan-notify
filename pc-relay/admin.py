"""admin.py — Web 管理后端（/admin/*）。

应用化（v2.1，SQLite 存储）：
- 保存配置 → 写入 DB + 热重载立即生效（token/手机地址/密码）；host/port 变更 → pending_restart
- 管理密码哈希存储（pbkdf2_sha256）；admin.secret 持久化在 DB，重启后会话保持
- 最近消息存 DB（messages 表），保留条数可配置（storage.recent_limit）
- POST /admin/api/restart：分离式子进程重启自身（uvicorn 重新绑定端口）
- 开机自启：GET /admin/api/autostart 状态 + POST 开关（复用 manage.py，仅 Windows）
- 登录（DB 密码哈希 → HttpOnly 会话 cookie，恒定时间比对）
- setup 模式：needs_setup 时 /admin/login 直接放行创建向导（见 setup_guard）

挂载：main.py 里 admin.bind() + app.include_router(router) + StaticFiles 挂 webui 产物。
"""

import asyncio
import base64
import hashlib
import hmac
import importlib.util
import json
import logging
import os
import secrets
import sys
import time
from pathlib import Path

import httpx
import subprocess as _subprocess

import store

# /api/restart 在 Windows 下的分离式子进程引用（防止被 GC 杀掉）
_subprocess_ref = [None]
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

# 注意：此处不可 import main —— python main.py 以 __main__ 运行时会造成循环导入
# （main 被当作新模块重新执行）。由 main.py 末尾调 bind() 注入自身引用。
relay_main = None


def bind(relay) -> None:
    """main.py 末尾注入主模块引用，供本模块运行时读配置/发请求。"""
    global relay_main
    relay_main = relay

log = logging.getLogger("pc-relay.admin")

router = APIRouter(prefix="/admin", include_in_schema=False)

# ---------------------------------------------------------------- 会话 cookie

COOKIE = "mihuan_admin"
TTL = 12 * 3600  # 会话 12h


def _sign(payload: bytes) -> str:
    return hmac.new(relay_main.C.admin_secret, payload, hashlib.sha256).hexdigest()


def make_token() -> str:
    payload = f"{int(time.time())}:{secrets.token_hex(8)}".encode()
    return base64.urlsafe_b64encode(payload).rstrip(b"=").decode() + "." + _sign(payload)


def check_token(token: str) -> bool:
    try:
        b64, sig = token.rsplit(".", 1)
        payload = base64.urlsafe_b64decode(b64 + "=" * (-len(b64) % 4))
        if not hmac.compare_digest(_sign(payload), sig):
            return False
        ts = int(payload.split(b":")[0])
        return time.time() - ts < TTL
    except Exception:
        return False


def authed(request: Request) -> bool:
    c = request.cookies.get(COOKIE, "")
    return bool(c) and check_token(c)


def err(status: int, error: str) -> JSONResponse:
    return JSONResponse({"ok": False, "error": error}, status_code=status)


def guard(handler):
    """API 鉴权装饰器：未登录 401。"""
    async def wrapper(request: Request):
        if not authed(request):
            return err(401, "unauthorized")
        return await handler(request)
    return wrapper


# ---------------------------------------------------------------- 消息记录

def record(envelope: dict, delivered: bool, attempts: int) -> None:
    """消息入库（main.py /webhook 与 /api/test 共用）。保留条数由 storage.recent_limit 控制。"""
    relay_main.C.add_message({
        "id": envelope["id"],
        "ts": envelope["ts"],
        "title": envelope["title"],
        "body": envelope["body"],
        "source": envelope.get("source"),
        "priority": envelope["priority"],
        "delivered": delivered,
        "attempts": attempts,
    })


# ---------------------------------------------------------------- 配置读写

# 结构化配置 schema（v2.0 表单式设置的数据源）
CONFIG_SCHEMA = {
    "server": {
        "label": "服务",
        "keys": {
            "host": {"label": "监听地址", "type": "string", "default": "0.0.0.0",
                     "help": "0.0.0.0=局域网可访问；127.0.0.1=仅本机。修改需重启。"},
            "port": {"label": "端口", "type": "int", "default": 8787,
                     "help": "修改需重启（保存后点「立即重启」）。"},
        },
    },
    "webhook": {
        "label": "Webhook（第三方应用接入）",
        "keys": {
            "token": {"label": "Webhook Token", "type": "secret", "default": "",
                      "help": "第三方应用调用 /webhook 时 Bearer 的 token。保存即生效。"},
        },
    },
    "phone": {
        "label": "手机端（直连模式）",
        "keys": {
            "url": {"label": "手机地址", "type": "string", "default": "",
                    "help": "形如 http://192.168.1.23:8788（手机 App 首页显示）。保存即生效。"},
            "token": {"label": "手机 Token", "type": "secret", "default": "",
                      "help": "与手机 App 显示的 Token 一致。保存即生效。"},
            "timeout_seconds": {"label": "超时(秒)", "type": "float", "default": 5, "help": ""},
            "retries": {"label": "重试次数", "type": "int", "default": 3, "help": "失败后重试次数。"},
        },
    },
    "relay": {
        "label": "中继服务器",
        "keys": {
            "mode": {"label": "传输模式", "type": "select", "options": [
                {"value": "direct", "label": "直连手机（局域网）"},
                {"value": "relay", "label": "经中继服务器"}],
                "default": "direct", "help": "直连=局域网直发手机；中继=推送到中继服务器，手机从中继拉取，可穿透 NAT/外网。保存即生效。"},
            "url": {"label": "中继服务器地址", "type": "string", "default": "",
                    "help": "形如 http://1.2.3.4:8080。中继模式必填。"},
            "push_token": {"label": "Push Token", "type": "secret", "default": "",
                           "help": "中继通道的 Push Token（中继 WebUI → 通道页复制）。中继模式必填。"},
            "channel_id": {"label": "通道 ID", "type": "string", "default": "",
                           "help": "中继通道的 6 位短 id。中继模式必填。"},
        },
    },
    "admin": {
        "label": "管理界面",
        "keys": {
            "password": {"label": "管理密码", "type": "secret", "default": "",
                         "help": "Web 管理界面登录密码（数据库内哈希存储）。保存即生效，下次登录用新密码。"},
            "secret": {"label": "会话密钥", "type": "secret", "default": "",
                       "help": "会话 cookie 签名密钥。更换后所有登录会话失效。"},
        },
    },
    "storage": {
        "label": "存储",
        "keys": {
            "recent_limit": {"label": "最近消息保留条数", "type": "int", "default": 200,
                             "help": "最近消息最多保留的条数，超出自动删除最旧的。保存即生效。"},
        },
    },
}


def _coerce(value, spec: dict, key_path: str):
    """按 schema 类型转换并校验单值，非法抛 ValueError（message 即 error code）。"""
    t = spec["type"]
    if t == "int":
        v = int(str(value).strip())
        if not (-2147483648 < v < 2147483648):
            raise ValueError("out_of_range")
        return v
    if t == "float":
        return float(str(value).strip())
    if t == "select":
        s = str(value).strip()
        allowed = [o["value"] for o in spec.get("options", [])]
        if allowed and s not in allowed:
            raise ValueError("invalid_value")
        return s
    s = str(value)
    if key_path.endswith(("token", "password", "secret")):
        # 秘密字段：允许打码占位符原样保留（未修改）；真实值可为任意非空串
        if "••" in s or s.startswith("(已设置)") or s == "__MASKED__":
            raise ValueError("masked_not_edited")  # 由调用方特判：跳过该键
    return s


@router.get("/api/config")
@guard
async def get_config(request: Request):
    """结构化配置（表单数据源）+ 运行状态。秘密值打码下发。"""
    C = relay_main.C
    values = {}
    for section, spec in CONFIG_SCHEMA.items():
        values[section] = {
            k: str(C.get(f"{section}.{k}", str(key.get("default", ""))))
            for k, key in spec["keys"].items()
        }
    hints = {
        "webhook_token_hint": _hint(values["webhook"]["token"]),
        "phone_token_hint": _hint(values["phone"]["token"]),
        "relay_push_token_hint": _hint(values["relay"]["push_token"]),
        "admin_password_hint": _hint(values["admin"]["password"]),
    }
    # 秘密值打码下发（前端表单不回显明文）；admin.password 存的是哈希，恒打码
    for sec_name, key in (("webhook", "token"), ("phone", "token"), ("relay", "push_token"), ("admin", "password"), ("admin", "secret")):
        if values[sec_name][key]:
            values[sec_name][key] = "__MASKED__"
    return JSONResponse({"ok": True, "schema": CONFIG_SCHEMA, "values": values,
                         "hints": hints, "state": C.describe()})


def _hint(v: str) -> str:
    v = str(v)
    if not v:
        return "（未设置）"
    return "(已设置)" if len(v) <= 4 else f"••••{v[-4:]}"


@router.post("/api/config")
@guard
async def save_config(request: Request):
    """表单保存：{"values": {section: {key: value}}} → 校验+类型转换+写 DB+热重载。
    返回 {ok, notes[], needs_restart, state}。"""
    try:
        data = await request.json()
    except Exception:
        return err(400, "invalid_json")

    C = relay_main.C
    values = data.get("values")
    if not isinstance(values, dict):
        return err(400, "invalid_values")

    # 类型校验 + 秘密字段打码跳过
    cleaned: dict[str, dict] = {}
    for section, keys in values.items():
        if section not in CONFIG_SCHEMA:
            return err(400, f"unknown_section_{section}")
        for k, v in keys.items():
            spec = CONFIG_SCHEMA[section]["keys"].get(k)
            if spec is None:
                return err(400, f"unknown_key_{section}.{k}")
            try:
                cv = _coerce(v, spec, f"{section}.{k}")
            except ValueError as e:
                if str(e) == "masked_not_edited":
                    continue  # 打码占位符未修改 → 保留原值
                return err(400, f"invalid_{section}.{k}")
            if spec["type"] == "secret" and not str(cv).strip():
                continue  # 秘密字段留空 → 保留原值（不误清空）
            cleaned.setdefault(section, {})[k] = cv

    # 端口/地址合法性
    if "port" in cleaned.get("server", {}):
        p = cleaned["server"]["port"]
        if not (1 <= p <= 65535):
            return err(400, "invalid_server.port")

    # admin.password：明文 → 哈希落库；admin.secret 直接落库（DB 持久化，重启不变）
    updates: dict[str, str] = {}
    if "password" in cleaned.get("admin", {}):
        raw_pw = cleaned["admin"].pop("password")
        updates["admin.password"] = store.hash_password(str(raw_pw))
    if "secret" in cleaned.get("admin", {}):
        raw_sec = cleaned["admin"].pop("secret")
        updates["admin.secret"] = str(raw_sec)

    for section, keys in cleaned.items():
        for k, v in keys.items():
            updates[f"{section}.{k}"] = str(v)

    old_host, old_port = C.host, C.port
    C.set_many(updates)
    C.reload()
    notes = []
    if (old_host, old_port) != (C.host, C.port):
        notes.append(f"监听地址将由 {old_host}:{old_port} 变为 {C.host}:{C.port}")
    return _save_result(C, notes)


def _save_result(C, notes: list[str] | None = None) -> dict:
    body = {
        "ok": True,
        "notes": notes or [],
        "needs_restart": C.pending_restart or (notes or []) != [],
        "state": C.describe(),
    }
    log.info("配置已保存并热重载 needs_setup=%s", C.needs_setup)
    return JSONResponse(body)


@router.post("/api/restart")
@guard
async def restart(request: Request):
    """重启服务进程（os.execve 替换自身，重新绑定新 host/port）。
    argv[0] 统一取 main.py 的真实路径（uvicorn CLI 启动时 sys.argv[0] 是
    uvicorn/__main__.py，直接 execve 会因脚本目录遮蔽标准库而崩溃）。"""
    import os as _os
    import sys as _sys
    log.info("收到重启指令，进程即将重启…")
    resp = JSONResponse({"ok": True, "note": "服务重启中，约 2-3 秒后恢复"})
    await asyncio.sleep(0.3)  # 让响应先发出去

    if getattr(_sys, "frozen", False):
        argv = [_sys.executable] + _sys.argv[1:]
    else:
        main_py = str(Path(__file__).resolve().parent / "main.py")
        argv = [_sys.executable, main_py]
    env = dict(_os.environ)
    if _os.name == "nt":
        # Windows 的 execve 是「父进程退出+子进程接管控制台」的近似实现，
        # 但 uvicorn 停机释放监听端口与子进程 bind 存在竞态（新进程可能
        # EADDRINUSE 退出且父进程已不在，导致服务整个消失）。
        # 改为分离式子进程 + 父进程延迟退出：子进程 bind 失败时自动重试，
        # 父进程给足停机时间后才退出，端口必然已释放。
        # 注意：不可加 DETACHED_PROCESS——PyInstaller console exe 在无控制台
        # 环境下 bootloader 立即退出（exit code 1）；继承父控制台即可。
        _subprocess_ref[0] = _subprocess.Popen(
            argv, env=env,
            creationflags=_subprocess.CREATE_NEW_PROCESS_GROUP,
            cwd=str(Path(_sys.executable).parent if getattr(_sys, "frozen", False) else Path(__file__).resolve().parent),
            close_fds=True,
        )
        await asyncio.sleep(2.0)  # 子进程 bind 重试窗口；随后父进程退出释放旧端口
        _os._exit(0)

    _os.execve(argv[0], argv, env)


@router.get("/api/status")
@guard
async def status(request: Request):
    C = relay_main.C
    uptime = time.time() - relay_main.STARTED_AT
    # 手机连通性：direct 模式 GET <phone>/ping；relay 模式 GET <relay>/channels/<id>/ping（2s 超时，不重试）
    phone_ok, phone_detail = False, ""
    if C.relay_mode == "relay":
        if C.relay_url and C.relay_channel_id and C.relay_push_token:
            try:
                async with httpx.AsyncClient(timeout=2.0) as client:
                    r = await client.get(
                        f"{C.relay_url}/channels/{C.relay_channel_id}/ping",
                        headers={"X-Push-Token": C.relay_push_token})
                phone_ok = r.status_code == 200
                phone_detail = f"HTTP {r.status_code}"
                if phone_ok:
                    pending = r.json().get("pending")
                    phone_detail = f"中继正常，待推送 {pending} 条"
            except Exception as e:
                phone_detail = type(e).__name__
        else:
            phone_detail = "中继服务器未配置完整"
    elif C.phone_url:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                r = await client.get(f"{C.phone_url}/ping")
                phone_ok = r.status_code == 200
                phone_detail = f"HTTP {r.status_code}"
        except Exception as e:
            phone_detail = type(e).__name__
    else:
        phone_detail = "未配置手机地址"
    return JSONResponse({
        "ok": True,
        "version": relay_main.VERSION,
        "uptime_sec": round(uptime),
        "mode": C.relay_mode,
        "phone": {"ok": phone_ok, "detail": phone_detail, "url": C.phone_url},
        "bind": f"{C.host}:{C.port}",
        "recent_count": relay_main.C.messages_count(),
        "state": C.describe(),
    })


# ---------------------------------------------------------------- Webhook 接入信息


def _candidate_ips() -> list[str]:
    """枚举本机可能的局域网 IPv4（不发包：UDP connect 探主地址 + 网卡枚举）。"""
    import ipaddress
    ips: list[str] = []
    # 首选：UDP connect 探默认路由出口地址（不真正发包）
    try:
        import socket as _s
        s = _s.socket(_s.AF_INET, _s.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass
    # 补充：所有非回环 IPv4（去重，保序）
    try:
        import psutil  # 可选依赖，打包不带
        for addrs in psutil.net_if_addrs().values():
            for a in addrs:
                if a.family == _s.AF_INET and a.address not in ips:
                    ips.append(a.address)
    except ImportError:
        try:
            hostname = __import__("socket").gethostname()
            for info in __import__("socket").getaddrinfo(hostname, None, _s.AF_INET):
                ip = info[4][0]
                if ip not in ips:
                    ips.append(ip)
        except OSError:
            pass
    return [ip for ip in ips if not ipaddress.ip_address(ip).is_loopback]


@router.get("/api/webhook-info")
async def webhook_info(request: Request):
    """Webhook 接入指引数据（无鉴权：不含 token 明文，只给 hint；登录页/向导页也能展示）。"""
    C = relay_main.C
    host_hdr = request.headers.get("host", "")  # 浏览器当前访问的主机:端口——最可靠的可达地址
    port = C.port
    base = f"http://{host_hdr}" if host_hdr else ""
    ips = _candidate_ips()
    urls = [f"http://{ip}:{port}" for ip in ips]
    if base and base not in urls:
        urls.insert(0, base)
    urls.insert(0, f"http://127.0.0.1:{port}")
    # 去重保序
    seen: set[str] = set()
    urls = [u for u in urls if not (u in seen or seen.add(u))]
    return JSONResponse({
        "ok": True,
        "urls": urls,               # 候选基地址（含 127.0.0.1 / 局域网 IP）
        "token_hint": _hint(C.webhook_token),
        "token_set": bool(C.webhook_token),
        "needs_setup": C.needs_setup,  # true = token 未下发完成，先完成首启向导
    })


@router.get("/api/webhook-token")
@guard
async def webhook_token(request: Request):
    """Webhook Token 明文（需登录）：供接入指引模板直接填入真实值。"""
    C = relay_main.C
    return JSONResponse({
        "ok": True,
        "token": C.webhook_token or "",
        "needs_setup": C.needs_setup,
    })


@router.post("/api/test")
@guard
async def test_send(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    C = relay_main.C
    if C.relay_mode == "relay":
        if not (C.relay_url and C.relay_push_token and C.relay_channel_id):
            return err(400, "relay_not_configured")
    elif not C.phone_url or not C.phone_token:
        return err(400, "phone_not_configured")
    envelope = {
        "v": 1,
        "id": relay_main.new_id(),
        "ts": relay_main.now_iso(),
        "title": str(data.get("title") or "测试通知")[:relay_main.MAX_TITLE],
        "body": str(data.get("body") or "这是一条来自 Web UI 的测试消息")[:relay_main.MAX_BODY_CHARS],
        "priority": data.get("priority") if data.get("priority") in ("normal", "high") else "normal",
        "source": "webui-test",
    }
    delivered, attempts = await relay_main.forward(envelope)
    record(envelope, delivered, attempts)
    return JSONResponse({"ok": True, "delivered": delivered, "attempts": attempts}, status_code=200)


@router.get("/api/recent")
@guard
async def recent_msgs(request: Request):
    return JSONResponse({"ok": True, "items": relay_main.C.get_messages()})


@router.post("/api/recent/clear")
@guard
async def recent_clear(request: Request):
    relay_main.C.clear_messages()
    return {"ok": True}


# ---------------------------------------------------------------- 开机自启（Windows）

def _load_manage():
    """加载 manage.py；加载不到返回 None。

    打包（frozen）后 manage.py 与 exe 同目录，而该目录不一定在 sys.path 上，
    因此这里按文件路径显式加载，避免 import 失败导致 Web UI 看不到自启开关。
    """
    try:
        import manage  # noqa: F401
        return manage
    except Exception:
        pass
    if getattr(sys, "frozen", False):
        cands = [Path(sys.executable).resolve().parent / "manage.py"]
    else:
        here = Path(__file__).resolve().parent
        cands = [here / "manage.py", here.parent / "manage.py"]
    for p in cands:
        try:
            if p.is_file():
                spec = importlib.util.spec_from_file_location("manage", p)
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                log.info("自启模块已从 %s 加载", p)
                return mod
        except Exception as exc:  # 单个候选失败不影响其它候选
            log.warning("自启模块加载失败 %s: %s", p, exc)
    return None


def _autostart_unavailable_hint() -> str:
    """不可用时的原因说明（Web UI 直接显示）。"""
    if os.name != "nt":
        return f"开机自启仅支持 Windows（当前系统：{os.name}）。"
    return "未找到 manage.py——它应与 pc-relay.exe 放在同一目录（发布包默认已含）。"


@router.get("/api/autostart")
@guard
async def autostart_status(request: Request):
    m = _load_manage()
    if m is None:
        return JSONResponse({"ok": True, "available": False, "enabled": False,
                             "hint": _autostart_unavailable_hint()})
    st = m.query_registry()
    return JSONResponse({"ok": True, "available": True, "enabled": st.enabled,
                         "mode": st.mode, "detail": st.detail})


@router.post("/api/autostart")
@guard
async def autostart_toggle(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = {}
    m = _load_manage()
    if m is None:
        return JSONResponse({"ok": False, "detail": _autostart_unavailable_hint()}, status_code=400)
    enable = bool(data.get("enable"))
    r = m.install() if enable else m.uninstall()
    return JSONResponse({"ok": r.get("ok", False), "detail": r.get("detail", "")})


# ---------------------------------------------------------------- 登录/登出

@router.post("/logout")
async def logout(request: Request):
    resp = JSONResponse({"ok": True})
    resp.delete_cookie(COOKIE)
    return resp


@router.post("/login")
async def login(request: Request):
    """登录。needs_setup（首启未配置）时无密码放行 → 前端直接进首启向导，
    设置密码后走正常登录。"""
    C = relay_main.C
    try:
        data = await request.json()
    except Exception:
        return err(400, "invalid_json")
    password = str(data.get("password", ""))

    if C.needs_setup and not C.admin_password_hash:
        # 首次配置模式：放行
        resp = JSONResponse({"ok": True, "setup": True})
        resp.set_cookie(COOKIE, make_token(), httponly=True, samesite="lax", max_age=TTL)
        return resp

    if not C.admin_password_hash:
        return err(400, "password_not_set")

    if not store.verify_password(password, C.admin_password_hash):
        await asyncio.sleep(0.5)  # 简单防爆破
        return err(401, "bad_credentials")
    resp = JSONResponse({"ok": True, "setup": False})
    resp.set_cookie(COOKIE, make_token(), httponly=True, samesite="lax", max_age=TTL)
    return resp
