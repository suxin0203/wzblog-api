package com.wzblog.modules.content.domain.event;

/**
 * 管理员对他人文章的治理动作（M3 据此通知作者；AFTER_COMMIT）。
 *
 * @param action       SOFT_DELETE / RESTORE / PURGE / TAKEOVER
 * @param articleTitle 文章标题（通知正文展示用，超长由监听器截断）
 */
public record ArticleModeratedEvent(Long articleId,
                                    Long authorId,
                                    Long moderatorId,
                                    String action,
                                    String reason,
                                    Integer version,
                                    String articleTitle) {
}
