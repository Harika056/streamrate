"""Mint an HS256 JWT for testing the gateway (standard library only).

  python scripts/make_jwt.py alice
  python scripts/make_jwt.py alice --secret "your JWT_SECRET" --minutes 60
"""
import argparse
import base64
import hashlib
import hmac
import json
import time

DEFAULT_SECRET = "dev-only-secret-change-me-0123456789abcdef"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token(subject: str, secret: str = DEFAULT_SECRET, minutes: int = 60) -> str:
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    now = int(time.time())
    payload = b64(json.dumps({"sub": subject, "iat": now, "exp": now + minutes * 60}, separators=(",", ":")).encode())
    sig = b64(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("subject")
    ap.add_argument("--secret", default=DEFAULT_SECRET)
    ap.add_argument("--minutes", type=int, default=60)
    a = ap.parse_args()
    print(make_token(a.subject, a.secret, a.minutes))
