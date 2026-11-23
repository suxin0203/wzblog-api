-- =============================================================================
-- 滑动窗口限流脚本（ZSET 实现，教学详解见 monorepo 根 docs/学习文档/13）
--
-- 【为什么必须用 Lua】"清理→计数→写入"是典型的 check-then-act：拆成三条独立命令时，
-- 并发请求会都查到"没超限"然后都写入（超发）。Redis 单线程执行 Lua 脚本 = 天然原子，
-- 一次网络往返完成全部步骤 —— 这是"分布式下 check-then-act 必须原子化"的标准范例。
--
-- 【算法】ZSET 的 score 存请求时间戳，member 存唯一标识（now-uuid）：
--   ① ZREMRANGEBYSCORE 清掉窗口外的旧记录（滑动的含义：只统计最近 window 内的请求）
--   ② ZCARD 数窗口内已有多少条
--   ③ 未超限 → ZADD 记入本次 + PEXPIRE 给整个 key 续期（防冷 key 永不过期泄漏内存）→ 放行
--   ④ 超限 → 取最老一条的 score，算出"它还要多久滑出窗口"作为建议重试等待
--
-- 入参（Java 侧见 common/ratelimit/SlidingWindowRateLimiter）：
--   KEYS[1] 限流 key（调用方组织维度，如 comment:u:123）
--   ARGV[1] now（毫秒）  ARGV[2] window（毫秒）  ARGV[3] limit  ARGV[4] member（唯一标识）
-- 返回：0=放行；>0=拒绝，值为建议重试等待毫秒
-- =============================================================================
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- ① 清理窗口外旧记录：score ∈ [0, now-window] 的都过期了
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

-- ②③ 未超限：记入本次请求并给 key 续期（每次活跃都重置 TTL，冷 key 自然过期被回收）
if redis.call('ZCARD', KEYS[1]) < limit then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    return 0
end

-- ④ 超限：最老记录滑出窗口还需多久 = (它的score + window) - now
--    调用方把它换算成 Retry-After 秒写进 429 响应头
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local wait = window
if oldest[2] then
    wait = math.max(0, tonumber(oldest[2]) + window - now)
end
return wait
