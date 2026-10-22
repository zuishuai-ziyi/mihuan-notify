"""store.py — relay-server 存储层：SQLite 单文件（配置 kv + 通道 + 消息队列）。

中继服务器是「消息 broker」：
- channels 表：通道（PC 推送 + 手机拉取，双 token 隔离）
- messages 表：信封 v1 队列，state: pending（待手机拉取）→ delivered（手机已 ack）
- kv 表：服务配置（监听/管理密码/保留条数），与 pc-relay v2.1 同风格

安全：
- 管理密码 PBKDF2 哈希存储（与 pc-relay 同格式）
- admin_secret 持久化，重启后会话保持
- push_token / poll_token 独立随机生成，泄露可单独轮换

Docker：路径 RELAY_DB > /app/data/relay.db（存在 data 目录时）> 源码目录 relay.db。
env RELAY_HOST / RELAY_PORT 强制覆盖监听配置（Docker 端口映射决定论）。
"""

import hashlib
import logging
import os
import secrets
import sqlite3
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

log = logging.getLogger("relay-server.store")

VERSION = "1.0.1"

# ---------------------------------------------------------------- 密码哈希（与 pc-relay 同格式）

PBKDF2_ITERS = 600_000
_HASH_PREFIX = "pbkdf2_sha256"


def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, PBKDF2_ITERS, dklen=32)
    return f"{_HASH_PREFIX}${PBKDF2_ITERS}${salt.hex()}${h.hex()}"


def verify_password(pw: str, stored: str) -> bool:
    try:
        algo, iters, salt_hex, hash_hex = stored.split("$", 3)
        if algo != _HASH_PREFIX:
            return False
        h = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), int(iters), dklen=32)
        return secrets.compare_digest(h.hex(), hash_hex)
    except (ValueError, TypeError):
        return False


def looks_like_hash(s: str) -> bool:
    return str(s).startswith(_HASH_PREFIX + "$")


# ---------------------------------------------------------------- Schema

DDL = """
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS channels (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    push_token TEXT NOT NULL,
    poll_token TEXT NOT NULL,
    created TEXT NOT NULL,
    last_poll REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id TEXT NOT NULL,
    msg_id TEXT NOT NULL,
    ts TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    source TEXT,
    priority TEXT NOT NULL DEFAULT 'normal',
    state TEXT NOT NULL DEFAULT 'pending',
    created TEXT NOT NULL,
    delivered_at TEXT,
    UNIQUE(channel_id, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_ch_seq ON messages(channel_id, seq);
"""

MAX_PENDING_PER_CHANNEL = 1000  # 手机长期不拉取时的积压保护（超出丢最旧的 pending）


def _now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def _seed_defaults() -> dict[str, str]:
    """新库种子默认值。Docker 友好：默认 0.0.0.0:8080，RELAY_ADMIN_PASSWORD 可设初始管理密码。"""
    return {
        "server.host": os.environ.get("RELAY_HOST", "0.0.0.0"),
        "server.port": os.environ.get("RELAY_PORT", "8080"),
        "admin.password": (hash_password(os.environ["RELAY_ADMIN_PASSWORD"])
                           if os.environ.get("RELAY_ADMIN_PASSWORD") else ""),
        "admin.secret": secrets.token_hex(32),
        "storage.recent_limit": "200",  # 每通道已送达消息保留条数
        "relay.max_wait": "30",         # 长轮询上限（秒）
    }


class Store:
    """SQLite 存储 + 运行时配置（单例，FastAPI 异步端点内的同步小查询）。"""

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

    # ---------------------------------------------------------------- 配置视图

    def reload(self) -> None:
        host = self.get("server.host", "0.0.0.0")
        try:
            port = int(self.get("server.port", "8080"))
        except ValueError:
            port = 8080
        # Docker：env 强制覆盖（端口映射需要确定性，UI 里改了也不生效）
        if os.environ.get("RELAY_HOST"):
            host = os.environ["RELAY_HOST"]
        if os.environ.get("RELAY_PORT"):
            try:
                port = int(os.environ["RELAY_PORT"])
            except ValueError:
                pass
        self.host, self.port = host, port

        pw = self.get("admin.password", "")
        self.admin_password_hash = pw

        secret_hex = self.get("admin.secret", "")
        try:
            self.admin_secret = bytes.fromhex(secret_hex)
        except ValueError:
            self.admin_secret = secret_hex.encode()

        try:
            self.recent_limit = max(1, int(self.get("storage.recent_limit", "200")))
        except ValueError:
            self.recent_limit = 200
        try:
            self.max_wait = min(60.0, max(1.0, float(self.get("relay.max_wait", "30"))))
        except ValueError:
            self.max_wait = 30.0

        self.needs_setup = not pw  # 未设管理密码 = 首启向导模式
        log.info("配置已加载 host=%s port=%s channels=%s needs_setup=%s",
                 self.host, self.port, self.channels_count(), self.needs_setup)

    def describe(self) -> dict:
        return {
            "needs_setup": self.needs_setup,
            "pending_restart": False,
            "parse_error": None,
            "created": self.created,
            "path": str(self.path),
        }

    # ---------------------------------------------------------------- 通道

    @staticmethod
    def _new_token() -> str:
        return secrets.token_hex(32)

    def create_channel(self, name: str) -> dict:
        cid = secrets.token_hex(6)
        with self._lk:
            self._db.execute(
                "INSERT INTO channels(id, name, push_token, poll_token, created) VALUES(?,?,?,?,?)",
                (cid, name, self._new_token(), self._new_token(), _now_iso()),
            )
            self._db.commit()
        log.info("通道已创建 id=%s name=%s", cid, name)
        return self.get_channel(cid)

    def get_channel(self, cid: str) -> dict | None:
        with self._lk:
            row = self._db.execute(
                "SELECT id, name, push_token, poll_token, created, last_poll FROM channels WHERE id=?",
                (cid,),
            ).fetchone()
        return self._ch_row(row) if row else None

    def list_channels(self) -> list[dict]:
        with self._lk:
            rows = self._db.execute(
                "SELECT id, name, push_token, poll_token, created, last_poll FROM channels ORDER BY rowid"
            ).fetchall()
        return [self._ch_row(r) for r in rows]

    def _ch_row(self, r) -> dict:
        d = {"id": r[0], "name": r[1], "push_token": r[2], "poll_token": r[3],
             "created": r[4], "last_poll": r[5]}
        d["pending"] = self.pending_count(r[0])
        # 在线判定：最后一次拉取在 90s 内（长轮询挂起期间也算活跃）
        d["online"] = (time.time() - r[5]) < 90 if r[5] else False
        return d

    def rename_channel(self, cid: str, name: str) -> None:
        with self._lk:
            self._db.execute("UPDATE channels SET name=? WHERE id=?", (name, cid))
            self._db.commit()

    def rotate_token(self, cid: str, which: str) -> str | None:
        if which not in ("push", "poll"):
            return None
        tok = self._new_token()
        with self._lk:
            cur = self._db.execute(
                f"UPDATE channels SET {which}_token=? WHERE id=?", (tok, cid)
            )
            self._db.commit()
        return tok if cur.rowcount else None

    def delete_channel(self, cid: str) -> bool:
        with self._lk:
            cur = self._db.execute("DELETE FROM channels WHERE id=?", (cid,))
            self._db.execute("DELETE FROM messages WHERE channel_id=?", (cid,))
            self._db.commit()
        return cur.rowcount > 0

    def channels_count(self) -> int:
        with self._lk:
            (n,) = self._db.execute("SELECT COUNT(*) FROM channels").fetchone()
        return n

    def touch_poll(self, cid: str) -> None:
        with self._lk:
            self._db.execute("UPDATE channels SET last_poll=? WHERE id=?", (time.time(), cid))
            self._db.commit()

    # ---------------------------------------------------------------- 消息队列

    def enqueue(self, cid: str, env: dict) -> bool:
        """信封入队。同通道 msg_id 重复 → 忽略返回 False（PC 重试场景幂等）。"""
        with self._lk:
            cur = self._db.execute(
                "INSERT OR IGNORE INTO messages(channel_id, msg_id, ts, title, body, source, priority, state, created) "
                "VALUES(?,?,?,?,?,?,?,'pending',?)",
                (cid, env["id"], env["ts"], env["title"], env["body"], env.get("source"),
                 env["priority"], _now_iso()),
            )
            if cur.rowcount == 0:
                return False
            self._trim_channel(cid)
            self._db.commit()
        return True

    def _trim_channel(self, cid: str) -> None:
        """已送达消息按 recent_limit 保留最新；pending 积压超限丢最旧。"""
        self._db.execute(
            "DELETE FROM messages WHERE channel_id=? AND state='delivered' AND seq NOT IN "
            "(SELECT seq FROM messages WHERE channel_id=? AND state='delivered' ORDER BY seq DESC LIMIT ?)",
            (cid, cid, self.recent_limit),
        )
        self._db.execute(
            "DELETE FROM messages WHERE channel_id=? AND state='pending' AND seq NOT IN "
            "(SELECT seq FROM messages WHERE channel_id=? AND state='pending' ORDER BY seq DESC LIMIT ?)",
            (cid, cid, MAX_PENDING_PER_CHANNEL),
        )

    def fetch_pending(self, cid: str, limit: int = 20) -> list[dict]:
        with self._lk:
            rows = self._db.execute(
                "SELECT msg_id, ts, title, body, source, priority FROM messages "
                "WHERE channel_id=? AND state='pending' ORDER BY seq LIMIT ?",
                (cid, max(1, min(limit, 100))),
            ).fetchall()
        return [
            {"v": 1, "id": r[0], "ts": r[1], "title": r[2], "body": r[3],
             "source": r[4], "priority": r[5]}
            for r in rows
        ]

    def ack(self, cid: str, msg_ids: list[str]) -> int:
        if not msg_ids:
            return 0
        with self._lk:
            cur = self._db.execute(
                f"UPDATE messages SET state='delivered', delivered_at=? "
                f"WHERE channel_id=? AND state='pending' AND msg_id IN "
                f"({','.join('?' * len(msg_ids))})",
                (_now_iso(), cid, *msg_ids),
            )
            self._db.commit()
        return cur.rowcount

    def pending_count(self, cid: str) -> int:
        with self._lk:
            (n,) = self._db.execute(
                "SELECT COUNT(*) FROM messages WHERE channel_id=? AND state='pending'", (cid,)
            ).fetchone()
        return n

    # ---------------------------------------------------------------- 最近消息（Web UI）

    def get_recent(self, channel_id: str | None = None, limit: int = 200) -> list[dict]:
        q = ("SELECT m.channel_id, c.name, m.msg_id, m.ts, m.title, m.body, m.source, m.priority, "
             "m.state, m.created, m.delivered_at FROM messages m LEFT JOIN channels c "
             "ON c.id = m.channel_id ")
        args: list = []
        if channel_id:
            q += "WHERE m.channel_id=? "
            args.append(channel_id)
        q += "ORDER BY m.seq DESC LIMIT ?"
        args.append(max(1, min(limit, 1000)))
        with self._lk:
            rows = self._db.execute(q, args).fetchall()
        return [
            {"channel_id": r[0], "channel": r[1], "id": r[2], "ts": r[3], "title": r[4],
             "body": r[5], "source": r[6], "priority": r[7],
             "state": r[8], "created": r[9], "delivered_at": r[10]}
            for r in rows
        ]

    def messages_count(self) -> int:
        with self._lk:
            (n,) = self._db.execute("SELECT COUNT(*) FROM messages").fetchone()
        return n

    def clear_messages(self, channel_id: str | None = None) -> None:
        with self._lk:
            if channel_id:
                self._db.execute("DELETE FROM messages WHERE channel_id=?", (channel_id,))
            else:
                self._db.execute("DELETE FROM messages")
            self._db.commit()

    def stats(self) -> dict:
        with self._lk:
            (ch,) = self._db.execute("SELECT COUNT(*) FROM channels").fetchone()
            (pending,) = self._db.execute(
                "SELECT COUNT(*) FROM messages WHERE state='pending'").fetchone()
            (msgs,) = self._db.execute("SELECT COUNT(*) FROM messages").fetchone()
        return {"channels": ch, "pending": pending, "messages": msgs}

    def close(self) -> None:
        with self._lk:
            self._db.close()


# ---------------------------------------------------------------- 工厂

def load_runtime() -> Store:
    """确定 DB 路径：RELAY_DB > /app/data/relay.db（Docker）> 源码目录 relay.db。"""
    if os.environ.get("RELAY_DB"):
        path = Path(os.environ["RELAY_DB"])
    elif Path("/app/data").is_dir():
        path = Path("/app/data/relay.db")
    else:
        path = Path(__file__).resolve().parent / "relay.db"
    return Store(path)
