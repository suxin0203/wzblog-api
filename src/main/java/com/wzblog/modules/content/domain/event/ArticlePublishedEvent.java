package com.wzblog.modules.content.domain.event;

import java.time.LocalDateTime;

/**
 * 文章首次发布事件（M2 → M4）。监听方使用 AFTER_COMMIT + {@code publish:{articleId}} 幂等。
 */
public record ArticlePublishedEvent(Long articleId, Long authorId, String title, LocalDateTime publishedAt) {
}