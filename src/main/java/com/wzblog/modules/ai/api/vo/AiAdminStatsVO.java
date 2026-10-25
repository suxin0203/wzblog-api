package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * 后台统计（M6 §14）。
 */
public record AiAdminStatsVO(Instant from, Instant to, long sessions, long messages,
                             long promptTokens, long completionTokens, long toolCalls,
                             long avgLatencyMs, long p95LatencyMs, long positiveFeedbacks,
                             long negativeFeedbacks, Double feedbackRate) {
}
