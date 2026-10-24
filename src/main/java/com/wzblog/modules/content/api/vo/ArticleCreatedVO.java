package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public record ArticleCreatedVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String slug,
        String status,
        int version,
        Instant publishedAt
) {
}
