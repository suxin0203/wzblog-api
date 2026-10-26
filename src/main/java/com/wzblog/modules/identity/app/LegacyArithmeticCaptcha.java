package com.wzblog.modules.identity.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.identity.infra.redis.CaptchaStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 旧协议算术验证码（兼容已上线小程序）：
 * GET /users/captcha → {captchaId, num1, num2}；客户端提交 captchaAnswer = MD5(num1+num2)。
 * 复用 M1 CaptchaStore（Redis + Lua 一次性消费），存储值为"答案和的 MD5"，
 * 校验时直接比对 MD5 串——绕过 M1 图形码的 4 位长度守卫（语义不同：这里是数学答案的哈希）。
 */
@Component
public class LegacyArithmeticCaptcha {

    private final CaptchaStore captchaStore;

    public LegacyArithmeticCaptcha(CaptchaStore captchaStore) {
        this.captchaStore = captchaStore;
    }

    public record Captcha(String captchaId, int num1, int num2) {
    }

    public Captcha generate() {
        int num1 = ThreadLocalRandom.current().nextInt(0, 20);
        int num2 = ThreadLocalRandom.current().nextInt(0, 20);
        String captchaId = UUID.randomUUID().toString();
        captchaStore.save(captchaId, md5Hex(String.valueOf(num1 + num2)));
        return new Captcha(captchaId, num1, num2);
    }

    /** 校验客户端提交的 MD5（答案和的哈希）；失败码与旧系统语义对齐。 */
    public void verify(String captchaId, String captchaAnswerMd5) {
        if (captchaId == null || captchaId.isBlank() || captchaAnswerMd5 == null
                || captchaAnswerMd5.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
        CaptchaStore.VerifyResult result = captchaStore.verify(captchaId,
                captchaAnswerMd5.trim().toLowerCase());
        switch (result) {
            case OK -> {
            }
            case EXPIRED -> throw new BusinessException(ErrorCode.AUTH_CAPTCHA_EXPIRED);
            default -> throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
