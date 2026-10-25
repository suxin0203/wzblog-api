package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * AI 会话视图。
 */
public record AiSessionVO(String sessionId, String title, String channel, int messageCount,
                          Instant lastMessageAt, Instant createdAt, Instant expiresAt) {
}
