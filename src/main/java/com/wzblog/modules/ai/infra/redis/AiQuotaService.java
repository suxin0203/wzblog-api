package com.wzblog.modules.ai.infra.redis;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.ai.infra.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 配额（M6 §11）：IP 分钟窗、登录用户/游客 IP 日窗，固定窗口 INCR（与 M3 偏差口径一致）。
 */
@Component
public class AiQuotaService {

    private final StringRedisTemplate redis;
    private final AiProperties properties;

    public AiQuotaService(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void checkAndConsume(String ip, Long userId) {
        long now = System.currentTimeMillis();
        String minuteKey = "ai:rl:ip:%s:%d".formatted(ip, now / 60_000);
        Long minute = redis.opsForValue().increment(minuteKey);
        if (minute != null && minute == 1L) {
            redis.expire(minuteKey, Duration.ofSeconds(120));
        }
        if (minute != null && minute > properties.getIpMinuteQuota()) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED, "提问过于频繁，请稍后再试",
                    60L);
        }

        int dailyLimit = userId != null ? properties.getDailyUserQuota()
                : properties.getGuestDailyQuota();
        String dailyKey = (userId != null ? "ai:rl:user:" + userId : "ai:rl:guest:" + ip)
                + ":" + (now / 86_400_000);
        Long daily = redis.opsForValue().increment(dailyKey);
        if (daily != null && daily == 1L) {
            redis.expire(dailyKey, Duration.ofDays(2));
        }
        if (daily != null && daily > dailyLimit) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED, "今日提问次数已用完", 3600L);
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
        String key = "ai:rl:ip:%s:%d".formatted(ip, System.currentTimeMillis() / 60_000);
        return new QuotaWindow(properties.getIpMinuteQuota(), parse(redis.opsForValue().get(key)));
    }

    public QuotaWindow dailyUsage(String ip, Long userId) {
        int limit = userId != null ? properties.getDailyUserQuota()
                : properties.getGuestDailyQuota();
        String key = (userId != null ? "ai:rl:user:" + userId : "ai:rl:guest:" + ip)
                + ":" + (System.currentTimeMillis() / 86_400_000);
        return new QuotaWindow(limit, parse(redis.opsForValue().get(key)));
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
