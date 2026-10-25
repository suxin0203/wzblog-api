package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * 反馈结果。
 */
public record AiFeedbackVO(String feedbackId, String messageId, int rating, Instant createdAt) {
}
