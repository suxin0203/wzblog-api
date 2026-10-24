package com.wzblog.modules.identity.infra.redis;

import com.wzblog.modules.identity.infra.config.AuthProperties;
import com.wzblog.modules.identity.infra.security.IdentityCrypto;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 图形验证码存储：{@code identity:captcha:{captchaId}} 为 Hash（hash、fails），5 分钟。
 */
@Component
public class CaptchaStore {

    public enum VerifyResult {
        OK, MISMATCH, EXPIRED, DISCARDED
    }

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final DefaultRedisScript<Long> verifyScript;

    public CaptchaStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.verifyScript = new DefaultRedisScript<>();
        this.verifyScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/captcha_verify.lua")));
        this.verifyScript.setResultType(Long.class);
    }

    public void save(String captchaId, String answer) {
        String key = IdentityKeys.captcha(captchaId);
        redis.opsForHash().putAll(key, Map.of(
                "hash", answerHash(captchaId, answer),
                "fails", "0"));
        redis.expire(key, properties.getCaptchaTtl());
    }

    public VerifyResult verify(String captchaId, String code) {
        Long result = redis.execute(verifyScript, List.of(IdentityKeys.captcha(captchaId)),
                answerHash(captchaId, code), String.valueOf(properties.getCaptchaMaxFails()));
        if (result == null) {
            return VerifyResult.EXPIRED;
        }
        return switch (result.intValue()) {
            case 1 -> VerifyResult.OK;
            case 0 -> VerifyResult.MISMATCH;
            case -2 -> VerifyResult.DISCARDED;
            default -> VerifyResult.EXPIRED;
        };
    }

    /** 答案哈希：SHA-256(captchaId:大写验证码)，忽略大小写。 */
    public static String answerHash(String captchaId, String code) {
        return IdentityCrypto.sha256Hex(captchaId + ":" + code.trim().toUpperCase());
    }

    public Duration ttl() {
        return properties.getCaptchaTtl();
    }
}