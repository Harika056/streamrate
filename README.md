# StreamRate

A rate-limiting API gateway. Clients are limited with a **token bucket stored in Redis and updated by one atomic Lua script**, so several gateway replicas share one limit per client.

- Spring Boot 3.3 + Spring Cloud Gateway (Java 17), reactive Redis client
- Identity: `X-API-Key` (maps to a plan) -> JWT subject -> client IP
- Over the limit: `429` with `Retry-After` and `X-RateLimit-*` headers
- JWT (HS256) required on `/secure/**`
- Fail-open by default if Redis is unreachable (configurable)
- Metrics at `/actuator/prometheus`, Grafana dashboard, nginx in front of 3 replicas (docker compose)

## What has and has not been verified
Verified by the author, with real runs:
- The Lua script, against a real Redis 7: burst equals capacity, refill over time, cap after idle, per-client isolation, TTL, and a 50-thread race on one bucket that never exceeds `capacity + refill * elapsed` (`pytest`).
- The helper scripts (`make_jwt.py`, `burst_check.py`).

Not verified in the environment where this was written (no Maven access there) - run these yourself and fix anything that shows up:
- `mvn verify` of `gateway` and `upstream`, and the Spring wiring (filters, Redis script call, JWT).
- `docker compose up`, nginx round-robin, Prometheus scrape, Grafana dashboard.
Treat the numbers in the table below as empty until you have measured them.

## How the limiter works
`gateway/src/main/resources/scripts/token_bucket.lua` keeps `tokens` and a timestamp in one Redis hash per client. Each call refills `elapsed * rate` tokens (capped at capacity), then spends one if available. It returns `{allowed, tokens_left, retry_after_ms}`. Redis `TIME` is used instead of the gateway's clock, so replicas with different clocks still agree. Because a Lua script runs atomically inside Redis, two replicas cannot both spend the same token.

## Run it (development: Redis in Docker, apps with Maven)
```
docker compose up -d redis
cd upstream && mvn spring-boot:run            # terminal 1, port 9000
cd gateway  && mvn spring-boot:run            # terminal 2, port 8080
```
Try it:
```
curl -i -H "X-API-Key: demo-free-key" http://localhost:8080/api/hello
python scripts/burst_check.py --api-key demo-free-key --requests 100 --threads 20 --capacity 10 --refill 2
python scripts/make_jwt.py alice
curl -i -H "Authorization: Bearer <token>" http://localhost:8080/secure/hello
```

## Full stack (3 replicas behind nginx)
```
docker compose up --build
```
nginx is on http://localhost:8080, Prometheus on :9090, Grafana on :3000 (dashboard "StreamRate"). Run `burst_check.py` against :8080 and compare the allowed count with one replica: it should not grow with the number of replicas, because the bucket lives in Redis.

## Configuration
Plans, API keys, fail-open and JWT settings are in `gateway/src/main/resources/application.yml`. Environment variables: `REDIS_HOST`, `UPSTREAM_URL`, `JWT_SECRET`, `TRUST_FORWARDED_FOR`. The bundled demo keys and JWT secret are for local use only.

## Tests
```
pip install -r requirements-test.txt
pytest                      # Lua script against redis-server, helper scripts
cd gateway && mvn test      # PlanResolver and RetryAfter unit tests
```

## Results (measured on a Windows laptop with Docker Desktop, one run each)
| Metric | Value | How measured |
|---|---|---|
| Java tests | 8 passed | `mvn test` in `gateway` |
| Allowed vs expected, 1 replica | 10 of 100 allowed, 90 limited (expected ~10.3) | `burst_check.py`, free plan: capacity 10, refill 2/s |
| Allowed vs expected, 3 replicas | 11 of 100 allowed, 89 limited (expected ~12.1) | `burst_check.py` via nginx; per-replica traffic 33 / 33 / 34 |
| Gateway latency p50 / p95 | 1 replica: 21.8 / 55.9 ms. 3 replicas via nginx: 47.1 / 1011 ms | `burst_check.py`; 100 requests only, p95 includes first-request start-up. Not a proper benchmark. |
