#!/usr/bin/env python3
"""隔离回归使用的回环 HTTP/WebSocket 上游；需要 websockets==15.0.1。"""
import asyncio
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
# JDK 的升级请求可能附带 Content-Length: 0；legacy HTTP 握手兼容这一空正文形式。
from websockets.legacy.server import serve

HTTP_PORT = int(os.environ.get('INTEGRATION_TEST_HTTP_PORT', '18888'))
WS_PORT = int(os.environ.get('INTEGRATION_TEST_WS_PORT', '18889'))
CLIENTS = set()
LOOP = None

async def disconnect():
    await asyncio.gather(*(client.close(code=1012, reason='test reconnect') for client in list(CLIENTS)), return_exceptions=True)

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/disconnect':
            asyncio.run_coroutine_threadsafe(disconnect(), LOOP).result(timeout=3)
        body = json.dumps({'rows': [{'city': '隔离测试城市', 'temperature': 26,
            'condition': 'TEST_BODY_MUST_NOT_BE_LOGGED', 'sequence': 1}]}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *args):
        pass

async def stream(connection):
    CLIENTS.add(connection)
    try:
        count = 0
        while True:
            count += 1
            await connection.send(json.dumps({'rows': [{'value': count, 'note': 'WS_BODY_MUST_NOT_BE_LOGGED'}]}))
            await asyncio.sleep(0.3)
    except Exception:
        pass
    finally:
        CLIENTS.discard(connection)

async def main():
    global LOOP
    LOOP = asyncio.get_running_loop()
    http = ThreadingHTTPServer(('127.0.0.1', HTTP_PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    async with serve(stream, '127.0.0.1', WS_PORT):
        print('Isolated upstream ready', flush=True)
        await asyncio.Future()

asyncio.run(main())
