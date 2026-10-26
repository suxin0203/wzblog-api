-- 滑动窗口限流（ZSET，原子：清理过期 → 计数 → 写入 → 续期）
-- KEYS[1] 限流 key
-- ARGV[1] now（毫秒）  ARGV[2] window（毫秒）  ARGV[3] limit  ARGV[4] member（唯一标识）
-- 返回：0=放行；>0=拒绝，值为建议重试等待毫秒
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

if redis.call('ZCARD', KEYS[1]) < limit then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    return 0
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local wait = window
if oldest[2] then
    wait = math.max(0, tonumber(oldest[2]) + window - now)
end
return wait
