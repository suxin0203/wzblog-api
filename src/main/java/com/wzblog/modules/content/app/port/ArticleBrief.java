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

    /** 跨模块消费方判断：已发布、公开、未删（避免外部触碰本模块枚举）。 */
    public boolean publiclyVisible() {
        return !deleted && status == ArticleStatus.PUBLISHED && visibility == ArticleVisibility.PUBLIC;
    }
}

