package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.wzblog.modules.content.domain.ArticleVisibility;

import java.time.Instant;
import java.util.List;

/**
 * 公开详情：html/toc 直接来自库中派生列，读取零渲染（M2 §7）。
 */
public record ArticleDetailVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String title,
        String slug,
        String summary,
        String coverKey,
        CategoryBriefVO category,
        List<TagBriefVO> tags,
        AuthorVO author,
        int viewCount,
        int likeCount,
        int favoriteCount,
        int commentCount,
        boolean isPinned,
        int readingSeconds,
        Instant publishedAt,
        String html,
        List<TocItemVO> toc,
        boolean allowComment,
        ArticleVisibility visibility,
        Instant createdAt,
        Instant updatedAt
) {
}
