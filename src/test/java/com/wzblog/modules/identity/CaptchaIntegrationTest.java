package com.wzblog.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.modules.identity.infra.redis.CaptchaStore;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图形验证码开启模式：PNG 输出、错 3 次作废、正确码一次性消费。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wzblog.auth.captcha-enabled=true")
@ActiveProfiles("test")
class CaptchaIntegrationTest extends IdentityTestSupport {

    @Test
    void captchaEndpointReturnsPngBase64() {
        HttpResult result = get("/api/v1/auth/captcha", null);
        assertThat(result.status()).isEqualTo(200);
        JsonNode data = result.data();
        assertThat(data.path("captchaId").asText()).isNotBlank();
        assertThat(data.path("imageBase64").asText()).startsWith("data:image/png;base64,");
        assertThat(data.path("expiresIn").asInt()).isEqualTo(300);

        String key = "identity:captcha:" + data.path("captchaId").asText();
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(1L, 300L);
    }

    @Test
    void captchaIsDiscardedAfterThreeWrongAnswers() {
        String captchaId = get("/api/v1/auth/captcha", null).data().path("captchaId").asText();

        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpResult wrong = registerWithCaptcha(unique("cp"), "ZZZZ", captchaId);
            assertThat(wrong.status()).as("attempt " + attempt).isEqualTo(400);
            assertThat(wrong.code()).isEqualTo("AUTH_CAPTCHA_INVALID");
        }
        HttpResult afterDiscard = registerWithCaptcha(unique("cp"), "ZZZZ", captchaId);
        assertThat(afterDiscard.status()).isEqualTo(400);
        assertThat(afterDiscard.code()).isEqualTo("AUTH_CAPTCHA_EXPIRED");
        assertThat(redis.hasKey("identity:captcha:" + captchaId)).isFalse();
    }

    @Test
    void registerSucceedsWithKnownCaptchaAndConsumesIt() {
        String captchaId = UUID.randomUUID().toString();
        String code = "ABCD";
        String key = "identity:captcha:" + captchaId;
        redis.opsForHash().putAll(key, Map.of(
                "hash", CaptchaStore.answerHash(captchaId, code),
                "fails", "0"));
        redis.expire(key, Duration.ofMinutes(5));

        String username = unique("cps");
        HttpResult result = registerWithCaptcha(username, code.toLowerCase(), captchaId);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("username").asText()).isEqualTo(username);
        assertThat(redis.hasKey(key)).isFalse();
    }

    private HttpResult registerWithCaptcha(String username, String captchaCode, String captchaId) {
        Map<String, Object> body = registerBody(username, "Passw0rd1", null, null);
        body.put("captchaId", captchaId);
        body.put("captchaCode", captchaCode);
        return post("/api/v1/auth/register", body, null);
    }
}