-- Sliding window peek, read-only w.r.t. capacity (does not consume it).
-- KEYS[1] = namespaced rate limit key
-- ARGV[1] = window seconds
-- ARGV[2] = limit
-- ARGV[3] = fixed now-millis, or "" to use Redis server clock
-- Returns  {current_count, remaining, reset_in_seconds}

local key    = KEYS[1]
local window = tonumber(ARGV[1])
local limit  = tonumber(ARGV[2])

local now
if ARGV[3] and ARGV[3] ~= '' then
  now = tonumber(ARGV[3])
else
  local t = redis.call('TIME')
  now = (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)
end

local windowMs = window * 1000

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)

local count = redis.call('ZCARD', key)

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local resetInSeconds
if #oldest == 0 then
  resetInSeconds = window
else
  local r = math.ceil((tonumber(oldest[2]) + windowMs - now) / 1000)
  if r < 0 then
    resetInSeconds = 0
  else
    resetInSeconds = r
  end
end

return {count, math.max(0, limit - count), resetInSeconds}
