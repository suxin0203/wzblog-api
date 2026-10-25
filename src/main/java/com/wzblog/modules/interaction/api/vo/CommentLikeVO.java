package com.wzblog.modules.interaction.api.vo;

/**
 * 评论点赞 toggle 结果。
 */
public record CommentLikeVO(String commentId, boolean liked, int likeCount) {
}
