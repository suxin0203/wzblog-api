package com.wzblog.modules.interaction.domain.event;

/**
 * 已过审评论被下架/删除事件（M4 反向积分）。
 */
public record CommentRemovedEvent(Long commentId, Long articleId, Long commenterId) {
}
