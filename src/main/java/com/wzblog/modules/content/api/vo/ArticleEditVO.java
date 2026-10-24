package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.wzblog.modules.content.domain.ArticleVisibility;

import java.time.Instant;
import java.util.List;

/**
 * 编辑详情：含 Markdown 原文与乐观锁版本（M2 §4.2）。
 */
public record ArticleEditVO(
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
        String contentMd,
        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> tagIds,
        ArticleVisibility visibility,
        boolean allowComment,
        String status,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
