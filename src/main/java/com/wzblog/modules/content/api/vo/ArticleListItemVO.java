package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.List;

/**
 * 信息流/搜索/置顶/热门列表项（M2 §4.2），不含 content_*。
 */
public record ArticleListItemVO(
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
        Instant publishedAt
) {
}
