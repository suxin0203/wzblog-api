package com.wzblog.modules.wechat.api.vo;

import java.time.LocalDateTime;

/**
 * POST /auth/qr/sessions 响应（M8 §4.2 QrSessionVO）：status 恒为 PENDING，
 * 客户端按契约自行拼 /code 地址取小程序码。
 */
public record QrSessionVO(String sceneId, String status, LocalDateTime expiresAt) {
}
