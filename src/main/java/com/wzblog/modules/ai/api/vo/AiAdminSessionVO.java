package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * 后台会话视图（不含 IP/UA/令牌）。
 */
public record AiAdminSessionVO(String sessionId, String userId, boolean guest, String title,
                               String channel, int messageCount, Instant lastMessageAt,
                               Instant createdAt) {
}
