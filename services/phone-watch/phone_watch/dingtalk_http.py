from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

# Thin response for existing web/HTTP clients; all new approvals are authored in PLM.
RETIRED_MESSAGE = "独立送审已停用，请从 PLM 手机新品监控发起开模申请"


class DingTalkSyncHandler(BaseHTTPRequestHandler):
    server_version = "PhoneWatchDingTalk/2.0"

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlparse(self.path).path in {"/", "/health", "/dingtalk/sync"}:
            self.send_json(200, {"ok": True, "enabled": False, "message": RETIRED_MESSAGE})
        else:
            self.send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        if urlparse(self.path).path == "/dingtalk/sync":
            self.send_json(410, {"ok": False, "error": RETIRED_MESSAGE})
        else:
            self.send_json(404, {"ok": False, "error": "not_found"})

    def log_message(self, format, *args):
        pass


def run_server(host="127.0.0.1", port=5011):
    print(RETIRED_MESSAGE, flush=True)
    ThreadingHTTPServer((host, port), DingTalkSyncHandler).serve_forever()
