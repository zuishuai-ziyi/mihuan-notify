"""store.py — pc-relay v2.1 存储层：SQLite 单文件数据库。

v2.1 架构变更：放弃 config.toml，配置+消息全部入库：
- kv 表：配置键值（扁平键 "server.host"），INSERT OR IGNORE 种子默认值 → 用户改过即保留
- messages 表：最近消息，INSERT 后按配置 recent_limit 截断；查询按 rowid 倒序
- 管理密码存哈希（pbkdf2_sha256$iters$salt_hex$hash_hex，600k 迭代），不再明文
- admin_secret 种子即持久化（v2.0 的"进程内随机、重启换钥"问题消除）——
  登录会话 cookie 签名密钥重启后不变，重启后会话保持
- WAL 模式 + 单连接 + RLock：FastAPI 单 worker 异步端点内的同步小查询，足够

路径：PC_RELAY_DB > exe 旁/源码目录 relay.db。旧 config.toml 不迁移（用户选择全新开始）。
"""

import hashlib
import logging
import os
import secrets
import sqlite3
import sys
import threading
from pathlib import Path

log = logging.getLogger("pc-relay.store")

# ---------------------------------------------------------------- 密码哈希

PBKDF2_ITERS = 600_000
_HASH_PREFIX = "pbkdf2_sha256"


def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, PBKDF2_ITERS, dklen=32)
    return f"{_HASH_PREFIX}${PBKDF2_ITERS}${salt.hex()}${h.hex()}"


def verify_password(pw: str, stored: str) -> bool:
    """校验密码与存储的哈希。格式不符一律 False。"""
    try:
        algo, iters, salt_hex, hash_hex = stored.split("$", 3)
        if algo != _HASH_PREFIX:
            return False
        h = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), int(iters), dklen=32)
        return secrets.compare_digest(h.hex(), hash_hex)
    except (ValueError, TypeError):
        return False


def looks_like_hash(s: str) -> bool:
    """配置值是否已是哈希格式（避免二次哈希）。"""
    return str(s).startswith(_HASH_PREFIX + "$")


# ---------------------------------------------------------------- Schema

DDL = """
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    ts TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    source TEXT,
    priority TEXT NOT NULL DEFAULT 'normal',
    delivered INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);
"""


def _seed_defaults() -> dict[str, str]:
    """新库种子默认值。首启安全默认：127.0.0.1 + 随机 webhook token + 随机会话密钥。"""
    port = os.environ.get("PC_RELAY_PORT", "8787")
    return {
        "server.host": "127.0.0.1",
        "server.port": port,
        "webhook.token": secrets.token_hex(32),
        "phone.url": "",
        "phone.token": "",
        "phone.timeout_seconds": "5",
        "phone.retries": "3",
        "relay.mode": "direct",        # direct=直连手机 / relay=经中继服务器
        "relay.url": "",              # 中继服务器 base URL（http(s)://host:port）
        "relay.push_token": "",        # 通道 push token
        "relay.channel_id": "",        # 通道 id（URL 拼接用）
        "admin.password": "",  # 空=未设置；设置密码时存哈希
        "admin.secret": secrets.token_bytes(32).hex(),
        "storage.recent_limit": "200",  # 最近消息保留条数（可在设置页修改）
    }


class Store:
    """SQLite 存储 + 运行时配置单例（duck-type v2.0 的 config.Config，main/admin 少改）。"""

    def __init__(self, path: Path):
        self.path = path
        self.created = not path.exists()
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lk = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(DDL)
        for k, v in _seed_defaults().items():
            self._db.execute("INSERT OR IGNORE INTO kv(key, value) VALUES(?,?)", (k, v))
        self._db.commit()

        self.needs_setup = False      # True = 手机地址/管理密码未配置（走首启向导）
        self.pending_restart = False  # host/port 改动待重启落地
        self.parse_error = None       # 兼容 describe()（DB 无解析错误，恒 None）
        self.reload()

    # ---------------------------------------------------------------- kv 读写

    def get(self, key: str, default: str = "") -> str:
        with self._lk:
            row = self._db.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        return row[0] if row else default

    def set_kv(self, key: str, value: str) -> None:
        with self._lk:
            self._db.execute(
                "INSERT INTO kv(key, value) VALUES(?,?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, str(value)),
            )
            self._db.commit()

    def set_many(self, items: dict[str, str]) -> None:
        with self._lk:
            for k, v in items.items():
                self._db.execute(
                    "INSERT INTO kv(key, value) VALUES(?,?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (k, str(v)),
                )
            self._db.commit()

    # ---------------------------------------------------------------- 配置视图（duck-type Config）

    def reload(self) -> None:
        """从 kv 重建配置属性。host/port 变化 → pending_restart=True。"""
        new_host = self.get("server.host", "127.0.0.1")
        try:
            new_port = int(self.get("server.port", "8787"))
        except ValueError:
            new_port = 8787
        if hasattr(self, "host") and (self.host, self.port) != (new_host, new_port):
            self.pending_restart = True
        self.host, self.port = new_host, new_port

        self.webhook_token = self.get("webhook.token", "")
        self.phone_url = self.get("phone.url", "").rstrip("/")
        self.phone_token = self.get("phone.token", "")
        try:
            self.phone_timeout = float(self.get("phone.timeout_seconds", "5"))
        except ValueError:
            self.phone_timeout = 5.0
        try:
            self.phone_retries = max(1, int(self.get("phone.retries", "3")))
        except ValueError:
            self.phone_retries = 3

        # 中继模式配置
        self.relay_mode = self.get("relay.mode", "direct")
        if self.relay_mode not in ("direct", "relay"):
            self.relay_mode = "direct"
        self.relay_url = self.get("relay.url", "").rstrip("/")
        self.relay_push_token = self.get("relay.push_token", "")
        self.relay_channel_id = self.get("relay.channel_id", "")

        # admin.password 存哈希；空=未设置
        pw = self.get("admin.password", "")
        self.admin_password_hash = pw
        self.admin_password = pw  # 兼容旧引用（值为哈希或空串，明文比对逻辑已改 verify_password）

        # admin.secret：kv 里持久化的 hex（种子即写入，重启不变）
        secret_hex = self.get("admin.secret", "")
        try:
            self.admin_secret = bytes.fromhex(secret_hex)
        except ValueError:
            self.admin_secret = secret_hex.encode()

        try:
            self.recent_limit = max(1, int(self.get("storage.recent_limit", "200")))
        except ValueError:
            self.recent_limit = 200

        # 可用判定：direct 模式需手机 url+token 齐备；relay 模式需中继 url+push token+通道 id 齐备
        if self.relay_mode == "relay":
            target_ok = bool(self.relay_url and self.relay_push_token and self.relay_channel_id)
        else:
            target_ok = bool(self.phone_url and self.phone_token)
        self.needs_setup = not (self.webhook_token and target_ok and pw)

        log.info(
            "配置已加载 host=%s port=%s phone=%s needs_setup=%s",
            self.host, self.port, self.phone_url or "(未配置)", self.needs_setup,
        )

    def describe(self) -> dict:
        """给 /admin/api/status 的运行时配置状态摘要。"""
        return {
            "needs_setup": self.needs_setup,
            "pending_restart": self.pending_restart,
            "parse_error": self.parse_error,
            "created": self.created,
            "path": str(self.path),
        }

    # ---------------------------------------------------------------- 消息

    def add_message(self, m: dict) -> None:
        with self._lk:
            self._db.execute(
                "INSERT OR IGNORE INTO messages(id, ts, title, body, source, priority, delivered, attempts) "
                "VALUES(?,?,?,?,?,?,?,?)",
                (m["id"], m["ts"], m["title"], m["body"], m.get("source"), m["priority"],
                 1 if m["delivered"] else 0, int(m.get("attempts", 0))),
            )
            # 超出保留条数：只保留最新 limit 条（按 rowid 倒序）
            self._db.execute(
                "DELETE FROM messages WHERE rowid NOT IN "
                "(SELECT rowid FROM messages ORDER BY rowid DESC LIMIT ?)",
                (self.recent_limit,),
            )
            self._db.commit()

    def get_messages(self) -> list[dict]:
        with self._lk:
            rows = self._db.execute(
                "SELECT id, ts, title, body, source, priority, delivered, attempts "
                "FROM messages ORDER BY rowid DESC"
            ).fetchall()
        return [
            {"id": r[0], "ts": r[1], "title": r[2], "body": r[3], "source": r[4],
             "priority": r[5], "delivered": bool(r[6]), "attempts": r[7]}
            for r in rows
        ]

    def messages_count(self) -> int:
        with self._lk:
            (n,) = self._db.execute("SELECT COUNT(*) FROM messages").fetchone()
        return n

    def clear_messages(self) -> None:
        with self._lk:
            self._db.execute("DELETE FROM messages")
            self._db.commit()

    def close(self) -> None:
        with self._lk:
            self._db.close()


# ---------------------------------------------------------------- 工厂

def load_runtime() -> Store:
    """main.py 调用：确定 DB 路径（PC_RELAY_DB > exe 旁/源码目录 relay.db）并创建单例。"""
    if getattr(sys, "frozen", False):
        default_dir = Path(sys.executable).parent
    else:
        default_dir = Path(__file__).parent
    db_path = Path(os.environ.get("PC_RELAY_DB") or default_dir / "relay.db")
    return Store(db_path)
