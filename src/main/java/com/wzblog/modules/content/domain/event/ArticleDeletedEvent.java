package com.wzblog.modules.content.domain.event;

/**
 * 文章软删事件（M2 → M4）。仅软删时发一次，恢复与彻底删除不重复发。
 */
public record ArticleDeletedEvent(Long articleId, Long authorId) {
}