-- Sliding window counter, atomic.
-- KEYS[1] = namespaced rate limit key
-- ARGV[1] = window seconds
-- ARGV[2] = limit
-- ARGV[3] = cost
-- ARGV[4] = unique request id (collision-free member naming)
-- ARGV[5] = fixed now-millis, or "" to use Redis server clock
-- Returns  {allowed, current_count, remaining, reset_in_seconds}

local key    = KEYS[1]
local window = tonumber(ARGV[1])
local limit  = tonumber(ARGV[2])
local cost   = tonumber(ARGV[3])
local reqId  = ARGV[4]

local now
if ARGV[5] and ARGV[5] ~= '' then
  now = tonumber(ARGV[5])
else
  local t = redis.call('TIME')
  now = (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)
end

local windowMs = window * 1000

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)

local count = redis.call('ZCARD', key)

local function resetIn(floorValue)
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  if #oldest == 0 then
    return window
  end
  local r = math.ceil((tonumber(oldest[2]) + windowMs - now) / 1000)
  if r < floorValue then
    return floorValue
  end
  return r
end

if count + cost <= limit then
  for i = 1, cost do
    redis.call('ZADD', key, now, now .. ':' .. reqId .. ':' .. i)
  end
  redis.call('PEXPIRE', key, windowMs)
  return {1, count + cost, limit - (count + cost), resetIn(0)}
else
  return {0, count, math.max(0, limit - count), resetIn(1)}
end
