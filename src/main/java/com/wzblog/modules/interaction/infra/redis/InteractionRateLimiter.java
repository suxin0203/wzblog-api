package com.wzblog.modules.interaction.infra.redis;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 互动限流（M3 §10）：固定窗口 INCR + 首次 EXPIRE，双窗口（短窗 + 日窗）。
 * <p>与架构 v2 的滑动窗口 Lua 相比，固定窗口在阈值边缘允许约 2x 突发，
 * 对博客评论场景可接受；窗口 key 带时间片编号，无需清理。</p>
 */
@Component
public class InteractionRateLimiter {

    private final StringRedisTemplate redis;

    public InteractionRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 检查并占用一个名额；超限抛 429。
     *
     * @param dimension  维度（如 u:{userId} / ip:{ip}）
     * @param scene      场景（comment/guestbook）
     * @param shortWindow 短窗（秒）与短窗上限
     * @param dailyLimit 日上限
     */
    public void checkAndConsume(String scene, String dimension, int shortWindow, int shortLimit,
                                int dailyLimit) {
        long now = System.currentTimeMillis();
        long shortSlot = now / 1000L / shortWindow;
        String shortKey = "rl:%s:%s:%d:%d".formatted(scene, dimension, shortWindow, shortSlot);
        Long shortCount = redis.opsForValue().increment(shortKey);
        if (shortCount != null && shortCount == 1L) {
            redis.expire(shortKey, Duration.ofSeconds(shortWindow * 2L));
        }
        if (shortCount != null && shortCount > shortLimit) {
            long retryAfter = shortWindow - (now / 1000L % shortWindow) + 1;
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "评论太频繁了，请稍后再试",
                    retryAfter);
        }

        String dayKey = "rl:%s:%s:d%d".formatted(scene, dimension, now / 86_400_000L);
        Long dayCount = redis.opsForValue().increment(dayKey);
        if (dayCount != null && dayCount == 1L) {
            redis.expire(dayKey, Duration.ofDays(2));
        }
        if (dayCount != null && dayCount > dailyLimit) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "今日评论次数已达上限", 3600L);
        }
    }
}
