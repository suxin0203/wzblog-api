package com.wzblog.modules.interaction.api.vo;

import java.time.Instant;

/**
 * 审核/留言状态变更结果。
 */
public record ModerationResultVO(String id, String status, Instant updatedAt) {
}
