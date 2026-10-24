package com.wzblog.modules.content.app.port;

import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;

/**
 * 跨模块文章摘要（M3/M6/M7），不包含正文派生列。
 */
public record ArticleBrief(
        Long id,
        Long authorId,
        String title,
        String slug,
        ArticleStatus status,
        ArticleVisibility visibility,
        boolean allowComment,
        boolean deleted
) {
}
