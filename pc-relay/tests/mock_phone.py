"""mock_phone — 手机端 /notify 的模拟服务，用于 PC 端转发测试（task-3/6）。

用法：python mock_phone.py [port]（默认 8799）
环境变量：
    MOCK_TOKEN      期望的 X-Auth-Token（默认 test-phone-token）
    MOCK_FAIL_FIRST 前 N 个请求返回 500（验证 PC 端重试逻辑）
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = os.environ.get("MOCK_TOKEN", "test-phone-token")
FAIL_FIRST = int(os.environ.get("MOCK_FAIL_FIRST", "0"))
state = {"n": 0}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        # 探活端点（与真实手机 App /ping 对齐，供 admin 状态检测用）
        if self.path == "/ping":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"ok":true,"service":"phone-relay","v":1}')
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        state["n"] += 1
        n = state["n"]
        if self.path != "/notify":
            self.send_response(404)
            self.end_headers()
            return
        if n <= FAIL_FIRST:
            print(f"[mock] #{n} 人为返回 500（触发重试）", flush=True)
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b'{"ok":false,"error":"internal"}')
            return
        if self.headers.get("X-Auth-Token", "") != TOKEN:
            print(f"[mock] #{n} 401 token 错误", flush=True)
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"ok":false,"error":"unauthorized"}')
            return
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            print(f"[mock] #{n} 400 JSON 非法", flush=True)
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b'{"ok":false,"error":"invalid_json"}')
            return
        print(
            f"[mock] #{n} 200 收到 id={data.get('id')} v={data.get('v')} "
            f"title={data.get('title')!r} body={data.get('body')!r} "
            f"source={data.get('source')!r} priority={data.get('priority')!r} ts={data.get('ts')!r}",
            flush=True,
        )
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *args):  # 静默默认访问日志
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
    print(f"[mock] 手机模拟端监听 127.0.0.1:{port} (token={TOKEN!r}, fail_first={FAIL_FIRST})", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
