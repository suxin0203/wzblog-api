package com.wzblog.modules.interaction.api.vo;

import java.time.Instant;

/**
 * 站内通知。
 */
public record NotificationVO(String id, String type, String title, String content,
                             String linkType, String linkId, AuthorVO actor, boolean isRead,
                             Instant readAt, Instant createdAt) {
}
