package com.wzblog.modules.interaction.infra.redis;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.ratelimit.SlidingWindowRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 互动限流（M3 §10）：滑动窗口 ZSET + Lua（公共组件），短窗 + 24h 滚动日窗。
 */
@Component
public class InteractionRateLimiter {

    private final SlidingWindowRateLimiter limiter;

    public InteractionRateLimiter(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * 检查并占用一个名额；超限抛 429，Retry-After 为窗口内最早记录的剩余等待秒数。
     */
    public void checkAndConsume(String scene, String dimension, int shortWindowSeconds,
                                int shortLimit, int dailyLimit) {
        String base = "rl:" + scene + ":" + dimension;
        var shortResult = limiter.check(base + ":short",
                Duration.ofSeconds(shortWindowSeconds), shortLimit);
        if (!shortResult.allowed()) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "评论太频繁了，请稍后再试",
                    shortResult.retryAfterSeconds());
        }
        var dailyResult = limiter.check(base + ":d24h", Duration.ofHours(24), dailyLimit);
        if (!dailyResult.allowed()) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "今日评论次数已达上限",
                    Math.min(dailyResult.retryAfterSeconds(), 86_400L));
        }
    }
}
