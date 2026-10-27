package com.wzblog.modules.ai.infra.redis;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.ratelimit.SlidingWindowRateLimiter;
import com.wzblog.modules.ai.infra.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * AI 配额（M6 §11）：IP 分钟窗 + 登录用户/游客 IP 24h 滚动日窗，
 * 滑动窗口 ZSET + Lua（公共组件）。并发 SSE 为独立计数器（INCR + 90s TTL 兜底）。
 */
@Component
public class AiQuotaService {

    private final StringRedisTemplate redis;
    private final SlidingWindowRateLimiter limiter;
    private final AiProperties properties;

    public AiQuotaService(StringRedisTemplate redis, SlidingWindowRateLimiter limiter,
                          AiProperties properties) {
        this.redis = redis;
        this.limiter = limiter;
        this.properties = properties;
    }

    public void checkAndConsume(String ip, Long userId) {
        var minute = limiter.check("ai:rl:ip:" + ip, Duration.ofSeconds(60),
                properties.getIpMinuteQuota());
        if (!minute.allowed()) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED, "提问过于频繁，请稍后再试",
                    minute.retryAfterSeconds());
        }

        int dailyLimit = userId != null ? properties.getDailyUserQuota()
                : properties.getGuestDailyQuota();
        String dailyKey = userId != null ? "ai:rl:user:" + userId : "ai:rl:guest:" + ip;
        var daily = limiter.check(dailyKey, Duration.ofHours(24), dailyLimit);
        if (!daily.allowed()) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED, "今日提问次数已用完",
                    Math.min(daily.retryAfterSeconds(), 86_400L));
        }
    }

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            buildAcquireScript();
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_SCRIPT =
            buildReleaseScript();

    /**
     * 并发 SSE 计数（Lua 原子）：INCR + 每次续期 + 超限回退一次完成。
     * 每次成功获取都续期 TTL，修复"仅值为 1 时设置 TTL 导致漂移为负"的问题。
     */
    public boolean tryAcquireConcurrency(String ip) {
        Long globalOk = redis.execute(ACQUIRE_SCRIPT, List.of("ai:conc:global"),
                String.valueOf(properties.getMaxConcurrent()), "90000");
        if (globalOk == null || globalOk == 0L) {
            return false;
        }
        Long ipOk = redis.execute(ACQUIRE_SCRIPT, List.of("ai:conc:ip:" + ip),
                String.valueOf(properties.getIpConcurrent()), "90000");
        if (ipOk == null || ipOk == 0L) {
            release("ai:conc:global"); // IP 维度失败，回退全局名额
            return false;
        }
        return true;
    }

    public void releaseConcurrency(String ip) {
        release("ai:conc:global");
        release("ai:conc:ip:" + ip);
    }

    private void release(String key) {
        redis.execute(RELEASE_SCRIPT, List.of(key));
    }

    private static org.springframework.data.redis.core.script.DefaultRedisScript<Long> buildAcquireScript() {
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                local c = redis.call('INCR', KEYS[1])
                redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
                if c > tonumber(ARGV[1]) then
                    redis.call('DECR', KEYS[1])
                    return 0
                end
                return 1
                """, Long.class);
        return script;
    }

    private static org.springframework.data.redis.core.script.DefaultRedisScript<Long> buildReleaseScript() {
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                local c = redis.call('DECR', KEYS[1])
                if c < 0 then
                    redis.call('SET', KEYS[1], 0)
                    redis.call('PEXPIRE', KEYS[1], 90000)
                end
                return c
                """, Long.class);
        return script;
    }

    public record QuotaWindow(int limit, long used) {
        public long remaining() {
            return Math.max(0, limit - used);
        }
    }

    public QuotaWindow minuteUsage(String ip) {
        return new QuotaWindow(properties.getIpMinuteQuota(),
                limiter.currentUsage("ai:rl:ip:" + ip, Duration.ofSeconds(60)));
    }

    public QuotaWindow dailyUsage(String ip, Long userId) {
        int limit = userId != null ? properties.getDailyUserQuota()
                : properties.getGuestDailyQuota();
        String key = userId != null ? "ai:rl:user:" + userId : "ai:rl:guest:" + ip;
        return new QuotaWindow(limit, limiter.currentUsage(key, Duration.ofHours(24)));
    }
}
