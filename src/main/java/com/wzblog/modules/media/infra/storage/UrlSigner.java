package com.wzblog.modules.media.infra.storage;

import com.wzblog.modules.media.infra.config.FileStorageProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * 能力 URL 签名器：本地磁盘模式下模拟对象存储的预签名语义。
 * <ul>
 *   <li>直传：{@code PUT /media-upload/{key}|{exp}|{sig}}，TTL = presignTtl；</li>
 *   <li>TEMP 短期读：{@code GET /media/{key}?exp=...&sig=...}，TTL 由调用方指定。</li>
 * </ul>
 * 签名内容为 {@code objectKey + '|' + epochSeconds}，HMAC-SHA256。
 */
@Component
public class UrlSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public UrlSigner(FileStorageProperties properties) {
        String secretValue = properties.getUrlSecret();
        if (secretValue == null || secretValue.length() < 32) {
            throw new IllegalStateException("wzblog.file.url-secret 未配置或长度不足 32 字节");
        }
        this.secret = secretValue.getBytes(StandardCharsets.UTF_8);
    }

    /** 能力用途前缀：读/写签名不可互换（防读签名重组为直传令牌覆盖已发布对象）。 */
    public static final String PURPOSE_PUT = "PUT:";
    public static final String PURPOSE_GET = "GET:";

    public String sign(String purpose, String objectKey, Instant expiresAt) {
        String payload = purpose + objectKey + "|" + expiresAt.getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    public boolean verify(String purpose, String objectKey, long expiresAtEpochSeconds,
                          String signature) {
        if (signature == null || Instant.now().getEpochSecond() > expiresAtEpochSeconds) {
            return false;
        }
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(purpose + objectKey + "|" + expiresAtEpochSeconds));
        return constantTimeEquals(expected, signature);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC 初始化失败", ex);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigestEqual.bytesEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** 常量时间比较，避免时序侧信道。 */
    static final class MessageDigestEqual {
        static boolean bytesEqual(byte[] a, byte[] b) {
            if (a.length != b.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < a.length; i++) {
                diff |= a[i] ^ b[i];
            }
            return diff == 0;
        }
    }
}
