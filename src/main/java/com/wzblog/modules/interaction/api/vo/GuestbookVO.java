package com.wzblog.modules.interaction.api.vo;

import java.time.Instant;

/**
 * 留言板条目。
 */
public record GuestbookVO(String id, AuthorVO author, String content, Instant createdAt,
                          boolean mine) {
}
