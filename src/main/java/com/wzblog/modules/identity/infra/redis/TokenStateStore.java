package com.wzblog.modules.identity.infra.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Access 黑名单、刷新宽限缓存、重置令牌与管理员守卫锁。
 */
@Component
public class TokenStateStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TokenStateStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** jti 进黑名单，TTL = access 剩余有效期。 */
    public void blacklist(String jti, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(IdentityKeys.tokenBlacklist(jti), "1", ttl);
    }

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(IdentityKeys.tokenBlacklist(jti)));
    }

    public void saveGrace(String oldTokenHash, GracePayload payload) {
        try {
            redis.opsForValue().set(IdentityKeys.refreshGrace(oldTokenHash),
                    objectMapper.writeValueAsString(payload), payload.ttl());
        } catch (Exception ignored) {
            // 宽限缓存失败不影响轮换结果
        }
    }

    public Optional<GracePayload> getGrace(String oldTokenHash) {
        String json = redis.opsForValue().get(IdentityKeys.refreshGrace(oldTokenHash));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, GracePayload.class));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public void saveResetToken(String tokenHash, Long userId, Duration ttl) {
        redis.opsForValue().set(IdentityKeys.reset(tokenHash), String.valueOf(userId), ttl);
    }

    /** 原子消费（GETDEL）。 */
    public Long consumeResetToken(String tokenHash) {
        String value = redis.opsForValue().getAndDelete(IdentityKeys.reset(tokenHash));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 最后管理员保护的短锁；未获得返回 false。 */
    public boolean tryAcquireAdminGuard() {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(IdentityKeys.adminGuard(), "1", Duration.ofSeconds(5)));
    }

    public void releaseAdminGuard() {
        redis.delete(IdentityKeys.adminGuard());
    }

    /**
     * 刷新宽限缓存载荷（10 秒）；{@code ttl} 不参与 JSON 之外的结构。
     */
    public record GracePayload(String accessToken, long accessExpiresIn,
                               String refreshToken, long refreshExpiresIn,
                               String uaHash, String familyId, long ttlSeconds) {

        public Duration ttl() {
            return Duration.ofSeconds(Math.max(1, ttlSeconds));
        }
    }
}