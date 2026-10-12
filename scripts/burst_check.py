"""Fire a burst of requests at the gateway and count how many were allowed (200) vs limited (429).

Example (free plan: capacity 10, refill 2/s):
  python scripts/burst_check.py --url http://localhost:8080/api/hello --api-key demo-free-key --requests 100 --threads 20

Expected allowed count is about  capacity + refill_rate * test_duration_seconds.
The script prints that expectation using the plan numbers you pass in, so you can compare it with what you got.
Works through nginx (3 replicas) the same way: the bucket is shared in Redis, so the total must not grow with replicas.
"""
import argparse
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor


def hit(url, headers):
    req = urllib.request.Request(url, headers=headers)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            code = resp.status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception:
        code = 0
    return code, (time.perf_counter() - t0) * 1000


def run(url, headers, requests, threads):
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=threads) as pool:
        results = list(pool.map(lambda _: hit(url, headers), range(requests)))
    elapsed = time.perf_counter() - started
    codes = Counter(c for c, _ in results)
    lat = sorted(ms for _, ms in results)
    return codes, lat, elapsed


def pct(sorted_values, p):
    return sorted_values[min(len(sorted_values) - 1, int(len(sorted_values) * p))]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8080/api/hello")
    ap.add_argument("--api-key", default=None)
    ap.add_argument("--bearer", default=None, help="JWT to send as Authorization: Bearer")
    ap.add_argument("--requests", type=int, default=100)
    ap.add_argument("--threads", type=int, default=20)
    ap.add_argument("--capacity", type=int, default=None, help="plan capacity, to print the expected allowed count")
    ap.add_argument("--refill", type=float, default=None, help="plan refill per second")
    a = ap.parse_args()

    headers = {}
    if a.api_key:
        headers["X-API-Key"] = a.api_key
    if a.bearer:
        headers["Authorization"] = f"Bearer {a.bearer}"

    codes, lat, elapsed = run(a.url, headers, a.requests, a.threads)
    allowed, limited = codes.get(200, 0), codes.get(429, 0)
    print(f"requests={a.requests} threads={a.threads} duration={elapsed:.2f}s")
    print(f"status counts: {dict(sorted(codes.items()))}")
    print(f"allowed(200)={allowed} limited(429)={limited} other={a.requests - allowed - limited}")
    print(f"latency ms: p50={pct(lat, 0.5):.1f} p95={pct(lat, 0.95):.1f} max={lat[-1]:.1f}")
    if a.capacity is not None and a.refill is not None:
        expected = a.capacity + a.refill * elapsed
        print(f"expected allowed ~= capacity + refill*duration = {a.capacity} + {a.refill}*{elapsed:.2f} = {expected:.1f}")


if __name__ == "__main__":
    main()
