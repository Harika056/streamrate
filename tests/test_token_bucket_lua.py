"""Tests the real Lua script against a real redis-server (started on a free port).

Skipped automatically if `redis-server` or the `redis` Python package is missing.
"""
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path

import pytest

redis = pytest.importorskip("redis")
pytestmark = pytest.mark.skipif(shutil.which("redis-server") is None, reason="redis-server not installed")

SCRIPT = (Path(__file__).resolve().parents[1] / "gateway/src/main/resources/scripts/token_bucket.lua").read_text()


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def server():
    port = _free_port()
    proc = subprocess.Popen(["redis-server", "--port", str(port), "--save", "", "--appendonly", "no"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    client = redis.Redis(port=port, decode_responses=True)
    for _ in range(50):
        try:
            client.ping()
            break
        except redis.ConnectionError:
            time.sleep(0.1)
    else:
        proc.kill()
        pytest.skip("redis-server did not start")
    yield port
    proc.terminate()
    proc.wait(timeout=5)


@pytest.fixture
def r(server):
    c = redis.Redis(port=server, decode_responses=True)
    c.flushall()
    return c


def acquire(r, key, capacity, rate, cost=1):
    return r.eval(SCRIPT, 1, key, capacity, rate, cost)


def test_burst_equals_capacity_then_denied(r):
    results = [acquire(r, "k", 5, 1)[0] for _ in range(8)]
    assert results == [1, 1, 1, 1, 1, 0, 0, 0]


def test_remaining_counts_down(r):
    assert [acquire(r, "k", 3, 1)[1] for _ in range(3)] == [2, 1, 0]


def test_denied_returns_retry_after(r):
    for _ in range(2):
        acquire(r, "k", 2, 2)
    allowed, _, retry_ms = acquire(r, "k", 2, 2)
    assert allowed == 0
    assert 1 <= retry_ms <= 500  # one token at 2/s is at most 500 ms away


def test_tokens_refill_over_time(r):
    for _ in range(5):
        acquire(r, "k", 5, 10)
    assert acquire(r, "k", 5, 10)[0] == 0
    time.sleep(0.35)  # ~3 tokens at 10/s
    got = sum(acquire(r, "k", 5, 10)[0] for _ in range(5))
    assert 2 <= got <= 4


def test_never_exceeds_capacity_after_idle(r):
    acquire(r, "k", 3, 100)       # creates the bucket, 2 tokens left
    time.sleep(0.3)               # uncapped this would refill 30 tokens
    got = sum(acquire(r, "k", 3, 100)[0] for _ in range(10))
    assert 3 <= got <= 4          # capped at capacity 3 (+ at most one token refilled while looping)


def test_clients_are_independent(r):
    for _ in range(3):
        acquire(r, "a", 3, 1)
    assert acquire(r, "a", 3, 1)[0] == 0
    assert acquire(r, "b", 3, 1)[0] == 1


def test_key_gets_a_ttl(r):
    acquire(r, "k", 10, 5)
    assert 0 < r.pttl("k") <= 4000  # 2 * 10/5 s = 4 s


def test_atomic_under_concurrency(server):
    """50 threads race on one bucket (capacity 20, refill 5/s). Allowed must never exceed
    capacity + refill * elapsed; a non-atomic read-modify-write would let extra requests through."""
    c0 = redis.Redis(port=server, decode_responses=True)
    c0.flushall()
    allowed = []
    lock = threading.Lock()
    start = time.time()

    def worker():
        c = redis.Redis(port=server, decode_responses=True)
        n = 0
        for _ in range(20):
            n += c.eval(SCRIPT, 1, "race", 20, 5, 1)[0]
        with lock:
            allowed.append(n)

    threads = [threading.Thread(target=worker) for _ in range(50)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    elapsed = time.time() - start
    total = sum(allowed)
    assert total <= 20 + 5 * elapsed + 1
    assert total >= 20
