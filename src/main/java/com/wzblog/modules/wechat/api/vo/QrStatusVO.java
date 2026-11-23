package com.wzblog.modules.wechat.api.vo;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * GET /auth/qr/sessions/{sceneId} 响应（M8 §4.2 QrStatusVO）：
 * 仅 CONFIRMED 返回 ticket（不消费）；COMPLETED/EXPIRED 恒为 null。
 * bindToken = CONFIRMED 后 60s 一次性凭据（B18 可选增强新增，仅会话创建者可见）：
 * 登录用途会话中网页通常改走 ticket 换票；「绑定微信」用途由网页凭它调
 * POST /auth/wechat/bind-by-token 完成绑定（消费后置 null）。
 * client = 扫码来源环境（snake_case，复用旧协议 describeClient，IP 已脱敏；v1.6.5 新增可选字段）。
 */
public record QrStatusVO(String sceneId, String status, String ticket, String bindToken,
                         LocalDateTime expiresAt, Map<String, Object> client) {
}
