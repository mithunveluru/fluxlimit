-- KEYS[1] state key
-- ARGV limit, window_micros, permits
-- returns {allowed, remaining, retry_after_micros}
local time = redis.call('TIME')
local now = time[1] * 1000000 + time[2]

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

local id = math.floor(now / window)
local offset = now - id * window

local cur = 0
local prev = 0
local state = redis.call('HMGET', KEYS[1], 'wid', 'cur', 'prev')
if state[1] then
  local wid = tonumber(state[1])
  cur = tonumber(state[2])
  prev = tonumber(state[3])
  if id == wid + 1 then
    prev = cur
    cur = 0
  elseif id > wid + 1 then
    prev = 0
    cur = 0
  end
end

-- previous window weighted by remaining overlap, rounded up
local contribution = math.ceil(prev * ((window - offset) / window))
local used = contribution + cur

local allowed = 0
local wait = 0
local remaining
if used + permits <= limit then
  cur = cur + permits
  allowed = 1
  remaining = limit - used - permits
else
  remaining = limit - used
  if remaining < 0 then
    remaining = 0
  end
  local budget = limit - cur - permits
  if budget >= 0 and prev > 0 then
    -- previous contribution decays linearly inside this window
    wait = (window - math.floor((budget / prev) * window)) - offset
  else
    -- cannot fit here, current becomes previous at the boundary
    wait = window - offset
    local nextbudget = limit - permits
    if cur > 0 and nextbudget >= 0 then
      wait = wait + (window - math.floor((nextbudget / cur) * window))
    end
  end
  if wait < 1 then
    wait = 1
  end
end

redis.call('HSET', KEYS[1], 'wid', id, 'cur', cur, 'prev', prev)
-- both windows stale after two window lengths
local ttl = math.ceil(window * 2 / 1000)
if ttl < 1 then
  ttl = 1
end
redis.call('PEXPIRE', KEYS[1], ttl)

return {allowed, remaining, wait}
