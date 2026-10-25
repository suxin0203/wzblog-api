package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * AI 消息视图（TOOL 行 content 为可读摘要）。
 */
public record AiMessageVO(String messageId, String role, String content, String status,
                          String toolName, Instant createdAt) {
}
