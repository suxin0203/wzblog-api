package com.wzblog.modules.ai.infra.redis;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.ratelimit.SlidingWindowRateLimiter;
import com.wzblog.modules.ai.infra.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    /** 并发 SSE 计数：进入 +1，完成/异常 -1（90s 兜底 TTL）。 */
    public boolean tryAcquireConcurrency(String ip) {
        String globalKey = "ai:conc:global";
        String ipKey = "ai:conc:ip:" + ip;
        Long global = redis.opsForValue().increment(globalKey);
        Long perIp = redis.opsForValue().increment(ipKey);
        redis.expire(ipKey, Duration.ofSeconds(90));
        if (global != null && global == 1L) {
            redis.expire(globalKey, Duration.ofSeconds(90));
        }
        if (global != null && global > properties.getMaxConcurrent()) {
            releaseConcurrency(ip);
            return false;
        }
        if (perIp != null && perIp > properties.getIpConcurrent()) {
            releaseConcurrency(ip);
            return false;
        }
        return true;
    }

    public void releaseConcurrency(String ip) {
        redis.opsForValue().decrement("ai:conc:global");
        redis.opsForValue().decrement("ai:conc:ip:" + ip);
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
