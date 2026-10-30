package com.wzblog.modules.wechat.api.vo;

import java.time.LocalDateTime;

/**
 * GET /auth/qr/sessions/{sceneId} 响应（M8 §4.2 QrStatusVO）：
 * 仅 CONFIRMED 返回 ticket（不消费）；COMPLETED/EXPIRED 恒为 null。
 */
public record QrStatusVO(String sceneId, String status, String ticket, LocalDateTime expiresAt) {
}
