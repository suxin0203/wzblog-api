package com.wzblog.modules.interaction.domain;

/**
 * 评论/留言审核状态机（M3 §6.1）。
 */
public enum CommentStatus {
    PENDING,
    APPROVED,
    REJECTED,
    HIDDEN
}
