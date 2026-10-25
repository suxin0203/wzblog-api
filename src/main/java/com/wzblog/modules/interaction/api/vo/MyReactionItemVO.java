package com.wzblog.modules.interaction.api.vo;

import java.time.Instant;

/**
 * 我的收藏/点赞列表项：文章摘要 + 互动时间。
 */
public record MyReactionItemVO(String articleId, String title, String slug, String summary,
                               Instant reactedAt) {
}
