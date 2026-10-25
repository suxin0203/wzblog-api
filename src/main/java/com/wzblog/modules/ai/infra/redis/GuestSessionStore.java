package com.wzblog.modules.ai.infra.redis;

import com.wzblog.modules.ai.infra.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 游客会话令牌：32 字节随机 Base64URL（wzai_ 前缀），Redis 只存 SHA-256，
 * TTL 24h；校验恒定时间比较（M6 §7）。
 */
@Component
public class GuestSessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AiProperties properties;

    public GuestSessionStore(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 为会话生成并登记令牌，返回明文（只此一次下发）。 */
    public String issue(String sessionId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "wzai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(sessionId), sha256Hex(token),
                Duration.ofHours(properties.getGuestSessionHours()));
        return token;
    }

    public boolean verify(String sessionId, String token) {
        if (token == null || !token.startsWith("wzai_")) {
            return false;
        }
        String stored = redis.opsForValue().get(key(sessionId));
        return stored != null && constantTimeEquals(stored, sha256Hex(token));
    }

    public void revoke(String sessionId) {
        redis.delete(key(sessionId));
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(key(sessionId)));
    }

    private static String key(String sessionId) {
        return "ai:guest:" + sessionId;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }
}
