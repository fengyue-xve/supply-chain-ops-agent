"""Internal Java calls must not send user JWTs through workstation HTTP proxies."""
import asyncio
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from app.tools.java_client import JavaToolClient


def test_internal_health_bypasses_environment_proxy(monkeypatch):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        def log_message(self, *args):
            pass

    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("ALL_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("NO_PROXY", "")
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    async def probe():
        client = JavaToolClient(f"http://127.0.0.1:{server.server_port}")
        try:
            assert await client.ping() is True
        finally:
            await client.aclose()
    try:
        asyncio.run(probe())
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
