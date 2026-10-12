import base64
import hashlib
import hmac
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import burst_check  # noqa: E402
import make_jwt  # noqa: E402


def _decode(part):
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


def test_jwt_is_valid_hs256():
    token = make_jwt.make_token("alice", secret="s" * 32, minutes=5)
    h, p, s = token.split(".")
    assert json.loads(_decode(h)) == {"alg": "HS256", "typ": "JWT"}
    claims = json.loads(_decode(p))
    assert claims["sub"] == "alice" and claims["exp"] > claims["iat"]
    expected = hmac.new(("s" * 32).encode(), f"{h}.{p}".encode(), hashlib.sha256).digest()
    assert _decode(s) == expected


def test_burst_check_counts_codes():
    state = {"n": 0}
    lock = threading.Lock()

    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            with lock:
                state["n"] += 1
                code = 200 if state["n"] <= 5 else 429
            self.send_response(code)
            self.end_headers()

        def log_message(self, *a):
            pass

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    codes, lat, elapsed = burst_check.run(f"http://127.0.0.1:{srv.server_port}/", {}, 20, 4)
    srv.shutdown()
    assert codes[200] == 5 and codes[429] == 15
    assert len(lat) == 20 and elapsed > 0
