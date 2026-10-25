package com.wzblog.modules.interaction.api.vo;

/**
 * 本人对文章的两种互动状态与计数。
 */
public record MyReactionVO(String articleId, boolean like, boolean favorite,
                           long likeCount, long favoriteCount) {
}
