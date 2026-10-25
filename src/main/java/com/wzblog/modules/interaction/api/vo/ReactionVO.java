package com.wzblog.modules.interaction.api.vo;

/**
 * 点赞/收藏设置结果。
 */
public record ReactionVO(String articleId, String type, boolean active, long count) {
}
