package com.wzblog.modules.identity.infra.redis;

import com.wzblog.modules.identity.infra.config.AuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 登录失败计数/锁定、IP 限流与每日首登抢占。
 */
@Component
public class LoginAttemptStore {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public LoginAttemptStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 密码错误计数 +1；首次写入时设置 15 分钟 TTL。 */
    public long incrementFailure(String usernameHash) {
        String key = IdentityKeys.loginFail(usernameHash);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, properties.getLoginLockTtl());
        }
        return count == null ? 0L : count;
    }

    public void clearFailures(String usernameHash) {
        redis.delete(IdentityKeys.loginFail(usernameHash));
    }

    public void lock(String usernameHash) {
        redis.opsForValue().set(IdentityKeys.loginLock(usernameHash), "1", properties.getLoginLockTtl());
    }

    public boolean isLocked(String usernameHash) {
        return Boolean.TRUE.equals(redis.hasKey(IdentityKeys.loginLock(usernameHash)));
    }

    /** 锁定剩余秒数，不存在返回 null。 */
    public Long lockRemainingSeconds(String usernameHash) {
        Long ttl = redis.getExpire(IdentityKeys.loginLock(usernameHash));
        return ttl == null || ttl <= 0 ? null : ttl;
    }

    public boolean tryAcquireLoginIp(String ip, int limit) {
        return tryAcquire(IdentityKeys.loginIp(ip), limit, Duration.ofMinutes(1));
    }

    public boolean tryAcquireRegisterIp(String ip) {
        return tryAcquire(IdentityKeys.registerIp(ip), properties.getRegisterIpLimitPerHour(), Duration.ofHours(1));
    }

    public boolean tryAcquireForgotEmail(String emailHash) {
        return tryAcquire(IdentityKeys.forgotEmail(emailHash),
                properties.getForgotEmailLimitPerHour(), Duration.ofHours(1));
    }

    public boolean tryAcquireForgotIp(String ip) {
        return tryAcquire(IdentityKeys.forgotIp(ip), properties.getForgotIpLimitPerHour(), Duration.ofHours(1));
    }

    private boolean tryAcquire(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null || count <= limit;
    }

    /**
     * 抢占业务日（Asia/Shanghai）首登标记，TTL 到当日 24:00 + 60s。
     *
     * @return true 表示当日首次
     */
    public boolean markFirstLoginOfDay(Long userId) {
        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        String key = IdentityKeys.loginDay(userId, businessDate.format(DAY_FORMAT));
        ZonedDateTime expireAt = businessDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).plusSeconds(60);
        Duration ttl = Duration.between(ZonedDateTime.now(BUSINESS_ZONE), expireAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(1);
        }
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }

    public int loginIpLimitPerMinute() {
        return properties.getLoginIpLimitPerMinute();
    }
}