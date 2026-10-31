package com.wzblog.modules.wechat.api.vo;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * GET /auth/qr/sessions/{sceneId} 响应（M8 §4.2 QrStatusVO）：
 * 仅 CONFIRMED 返回 ticket（不消费）；COMPLETED/EXPIRED 恒为 null。
 * client = 扫码来源环境（snake_case，复用旧协议 describeClient，IP 已脱敏；v1.6.5 新增可选字段）。
 */
public record QrStatusVO(String sceneId, String status, String ticket, LocalDateTime expiresAt,
                         Map<String, Object> client) {
}
