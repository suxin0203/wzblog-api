package com.wzblog.common.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 滑动窗口限流（ZSET + Lua，一次网络往返原子完成）。
 * <p>相对固定窗口无边界 2x 突发问题；相对令牌桶无需补填速率，适合"每 N 秒/每天 M 次"类配额。
 * Spring Data Redis 按 SHA 缓存脚本，重复调用走 EVALSHA。</p>
 */
@Component
public class SlidingWindowRateLimiter {

    private static final RedisScript<Long> SCRIPT = buildScript();

    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 判定结果：allowed=false 时 retryAfterSeconds ≥1。 */
    public record Verdict(boolean allowed, long retryAfterSeconds) {
    }

    public Verdict check(String key, Duration window, int limit) {
        long now = System.currentTimeMillis();
        String member = now + "-" + UUID.randomUUID();
        Long waitMs = redis.execute(SCRIPT, List.of(key),
                String.valueOf(now),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                member);
        long wait = waitMs == null ? 0L : waitMs;
        return new Verdict(wait <= 0, wait <= 0 ? 0 : Math.max(1, (wait + 999) / 1000));
    }

    /** 只读查询当前窗口已用名额（清理过期后 ZCARD，非原子，仅供配额展示）。 */
    public long currentUsage(String key, Duration window) {
        long cutoff = System.currentTimeMillis() - window.toMillis();
        redis.opsForZSet().removeRangeByScore(key, 0, cutoff);
        Long size = redis.opsForZSet().zCard(key);
        return size == null ? 0 : size;
    }

    private static RedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/rate_limit_sliding_window.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
