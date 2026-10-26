package com.wzblog.modules.wechat.infra.wechat;

import com.wzblog.modules.wechat.infra.config.WechatProperties;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 上游：确定性 openid（同一 code → 同一 openid，模拟微信 code 语义用递增计数），
 * 小程序码为程序生成的 PNG（黑底白字方块）。测试与无凭据环境全链路可用。
 */
public class MockWeChatApiClient implements WeChatApiClient {

    /** 测试可控：code 前缀映射到固定 openid；未登记的 code 生成确定性 openid。 */
    public static final Map<String, String> CODE_TO_OPENID = new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicLong COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    private final WechatProperties properties;

    public MockWeChatApiClient(WechatProperties properties) {
        this.properties = properties;
    }

    @Override
    public SessionResult code2Session(String code) {
        if (code == null || code.isBlank()) {
            throw new com.wzblog.common.error.BusinessException(
                    com.wzblog.common.error.ErrorCode.WECHAT_CODE_INVALID);
        }
        if ("mock-bad-code".equals(code)) {
            throw WeChatApiClient.mapUpstreamError(40029, "invalid code");
        }
        String openid = CODE_TO_OPENID.computeIfAbsent(code,
                key -> "mock-openid-" + COUNTER.incrementAndGet());
        return new SessionResult(openid, null);
    }

    @Override
    public byte[] getQrCodePng(String sceneId, String envVersion) {
        try {
            BufferedImage image = new BufferedImage(280, 280, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 280, 280);
            graphics.setColor(Color.BLACK);
            // 用 sceneId 确定性画"伪二维码"方块阵（视觉上像码即可，内容无需可扫）
            int seed = Math.abs(sceneId.hashCode());
            for (int y = 0; y < 14; y++) {
                for (int x = 0; x < 14; x++) {
                    if (((seed >> ((x * 14 + y) % 31)) & 1) == 1) {
                        graphics.fillRect(20 + x * 17, 20 + y * 17, 15, 15);
                    }
                }
            }
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String configuredEnvVersion() {
        return properties.getEnvVersion();
    }
}
