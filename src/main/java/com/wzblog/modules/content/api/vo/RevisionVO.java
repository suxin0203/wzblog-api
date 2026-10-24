package com.wzblog.modules.content.api.vo;

import java.time.Instant;

/**
 * 修订列表项；不返回 contentMd（M2 §4.2）。
 */
public record RevisionVO(
        int revisionNo,
        AuthorVO editor,
        String title,
        String summary,
        String changeNote,
        Instant createdAt
) {
}
