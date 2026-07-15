-- KEYS[1] state key
-- ARGV capacity_micros, refill_tokens, period_micros, permits_micros
-- returns {allowed, remaining_whole_tokens, retry_after_micros}
local time = redis.call('TIME')
local now = time[1] * 1000000 + time[2]

local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local period = tonumber(ARGV[3])
local permits = tonumber(ARGV[4])
-- micro-tokens per microsecond, division first keeps doubles exact-ish
local rate = refill * 1000000 / period

local tokens = capacity
local ts = now
local state = redis.call('HMGET', KEYS[1], 'tk', 'ts')
if state[1] then
  tokens = tonumber(state[1])
  ts = tonumber(state[2])
  if tokens > capacity then
    tokens = capacity
  end
  local elapsed = now - ts
  if elapsed > 0 then
    local added = math.floor(elapsed * rate)
    if added >= capacity - tokens then
      tokens = capacity
      ts = now
    else
      tokens = tokens + added
      -- advance only by consumed time, keep the remainder
      ts = ts + math.floor(added / rate)
    end
  end
end

local allowed = 0
local wait = 0
if tokens >= permits then
  tokens = tokens - permits
  allowed = 1
else
  wait = math.ceil((permits - tokens) / rate)
  if wait > 1e15 then
    wait = 1e15
  end
end

redis.call('HSET', KEYS[1], 'tk', tokens, 'ts', ts)
-- expire once the bucket is full again, one period of grace
local ttl = math.ceil(((capacity - tokens) / rate + period) / 1000)
if ttl < 1 then
  ttl = 1
elseif ttl > 1e12 then
  ttl = 1e12
end
redis.call('PEXPIRE', KEYS[1], ttl)

return {allowed, math.floor(tokens / 1000000), wait}
