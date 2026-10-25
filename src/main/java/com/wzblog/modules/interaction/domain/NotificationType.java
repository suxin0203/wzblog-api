package com.wzblog.modules.interaction.domain;

/**
 * 通知类型（00 §6.1，13 个值，与 notifications.ck 约束一致）。
 */
public enum NotificationType {
    COMMENT_REPLY,
    COMMENT_LIKE,
    ARTICLE_COMMENTED,
    ARTICLE_LIKED,
    ARTICLE_FAVORITED,
    COMMENT_APPROVED,
    ORDER_APPROVED,
    ORDER_SHIPPED,
    ORDER_COMPLETED,
    ORDER_REJECTED,
    ORDER_CANCELLED,
    POINTS_ADJUSTED,
    SYSTEM
}
