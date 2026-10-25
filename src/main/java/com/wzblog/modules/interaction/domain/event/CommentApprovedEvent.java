package com.wzblog.modules.interaction.domain.event;

/**
 * 评论过审事件（M4 积分按 comment_approved:{commentId} 幂等消费）。
 */
public record CommentApprovedEvent(Long commentId, Long articleId, Long commenterId,
                                   String articleTitle) {
}
