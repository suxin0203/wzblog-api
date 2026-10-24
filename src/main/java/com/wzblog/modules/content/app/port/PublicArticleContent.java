package com.wzblog.modules.content.app.port;

import java.time.LocalDateTime;

/**
 * AI 工具只读文章：仅 PUBLISHED+PUBLIC，纯文本截断 6000 字符（M2 §16.1）。
 */
public record PublicArticleContent(
        Long id,
        Long authorId,
        String title,
        String slug,
        String contentText,
        LocalDateTime publishedAt
) {
}
