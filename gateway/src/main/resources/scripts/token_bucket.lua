-- Atomic token bucket. One Redis hash per client: fields `tokens` and `ts` (ms).
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (max burst), ARGV[2] = refill tokens per second, ARGV[3] = cost
-- Returns { allowed (1/0), tokens_left (floored), retry_after_ms }
-- Time comes from Redis (TIME), not from the gateway, so replicas with skewed clocks agree.
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])

local t      = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now_ms
end

local elapsed = math.max(0, now_ms - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)

local allowed = 0
local retry_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_ms = math.ceil((cost - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now_ms)
-- Idle buckets disappear after twice the time needed to refill completely.
local ttl_ms = math.max(1000, math.ceil(capacity / rate * 1000) * 2)
redis.call('PEXPIRE', KEYS[1], ttl_ms)

return { allowed, math.floor(tokens), retry_ms }
