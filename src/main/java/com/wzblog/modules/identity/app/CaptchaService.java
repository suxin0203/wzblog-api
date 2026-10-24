package com.wzblog.modules.identity.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.identity.api.vo.CaptchaVO;
import com.wzblog.modules.identity.infra.config.AuthProperties;
import com.wzblog.modules.identity.infra.redis.CaptchaStore;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码：Java2D 生成 4 位 PNG，答案哈希存 Redis，5 分钟、一次性、错 3 次作废。
 */
@Service
public class CaptchaService {

    /** 排除易混字符 0O1Il。 */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CaptchaStore captchaStore;
    private final AuthProperties properties;

    public CaptchaService(CaptchaStore captchaStore, AuthProperties properties) {
        this.captchaStore = captchaStore;
        this.properties = properties;
    }

    public CaptchaVO generate() {
        String captchaId = UUID.randomUUID().toString();
        char[] chars = new char[4];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        String code = new String(chars);
        captchaStore.save(captchaId, code);
        return new CaptchaVO(captchaId, "data:image/png;base64," + render(code),
                (int) captchaStore.ttl().toSeconds());
    }

    /**
     * 消费图形验证码；验证码关闭时直接通过。
     */
    public void verify(String captchaId, String captchaCode) {
        if (!properties.isCaptchaEnabled()) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null
                || captchaCode.trim().length() != 4) {
            throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
        CaptchaStore.VerifyResult result = captchaStore.verify(captchaId, captchaCode);
        switch (result) {
            case OK -> {
            }
            case EXPIRED -> throw new BusinessException(ErrorCode.AUTH_CAPTCHA_EXPIRED);
            default -> throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
    }

    private String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(160 + RANDOM.nextInt(80), 160 + RANDOM.nextInt(80),
                        160 + RANDOM.nextInt(80)));
                g.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT),
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(20 + RANDOM.nextInt(90), 20 + RANDOM.nextInt(90),
                        20 + RANDOM.nextInt(90)));
                double angle = (RANDOM.nextDouble() - 0.5) * 0.5;
                int x = 12 + i * 26;
                int y = 30 + RANDOM.nextInt(6) - 3;
                g.rotate(angle, x, y);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.rotate(-angle, x, y);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("验证码生成失败", ex);
        }
    }
}