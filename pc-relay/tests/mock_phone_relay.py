"""mock_phone_relay — 模拟手机端「中继模式」长轮询（Android RelayPoller.kt 的等价实现）。

用法：python mock_phone_relay.py <base_url> <channel_id> <poll_token>
环境变量：
    MOCK_RELAY_STOP  该文件存在时退出循环（脚本用它停止）
    MOCK_RELAY_ONCE  置 1 则只跑一轮后退出（单测用）
输出（stdout，逐行 flush）：
    [relay] poll n=<收到条数>
    [relay] 收到 id=<id> priority=<p> title=<title>
    [relay] ack <ids>
    [relay] 错误 <Type>: <msg>
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = sys.argv[1].rstrip("/")
CID = sys.argv[2]
TOKEN = sys.argv[3]
STOP = os.environ.get("MOCK_RELAY_STOP", "")
ONCE = os.environ.get("MOCK_RELAY_ONCE") == "1"
WAIT = 5
LIMIT = 10


def http(method: str, path: str, body: dict | None = None, timeout: int = 30) -> dict:
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-Poll-Token", TOKEN)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def main() -> None:
    print(f"[relay] 启动 base={BASE} cid={CID}", flush=True)
    backoff = 3
    while True:
        try:
            q = urllib.parse.urlencode({"wait": WAIT, "limit": LIMIT})
            d = http("GET", f"/channels/{CID}/messages?{q}", timeout=WAIT + 15)
            msgs = d.get("messages") or []
            print(f"[relay] poll n={len(msgs)}", flush=True)
            if msgs:
                for m in msgs:
                    print(f"[relay] 收到 id={m.get('id')} priority={m.get('priority')} "
                          f"title={m.get('title')}", flush=True)
                ids = [m["id"] for m in msgs if m.get("id")]
                if ids:
                    ack = http("POST", f"/channels/{CID}/ack", {"ids": ids})
                    print(f"[relay] ack {ack.get('acked')}", flush=True)
            backoff = 3
        except Exception as e:  # noqa: BLE001 - 冒烟脚本，任何错都只记录并退避
            print(f"[relay] 错误 {type(e).__name__}: {e}", flush=True)
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)
        if ONCE:
            return
        if STOP and os.path.exists(STOP):
            print("[relay] 收到停止信号，退出", flush=True)
            return
        time.sleep(0.2)


if __name__ == "__main__":
    main()
