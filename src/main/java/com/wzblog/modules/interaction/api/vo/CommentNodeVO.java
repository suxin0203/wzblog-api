package com.wzblog.modules.interaction.api.vo;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * 评论节点（M3 §4.2）。管理列表额外携带 articleTitle/ip。
 */
@Builder
public record CommentNodeVO(String id, String articleId, String rootId, String parentId,
                            AuthorVO author, AuthorVO replyToUser, String content, String status,
                            int likeCount, boolean liked, int replyCount, boolean hasMoreReplies,
                            boolean deleted, List<CommentNodeVO> children, Instant createdAt,
                            String articleTitle, String ip) {
}
