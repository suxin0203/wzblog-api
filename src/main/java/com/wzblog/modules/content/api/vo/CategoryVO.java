package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public record CategoryVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name,
        String slug,
        String description,
        int sortOrder,
        String status,
        long articleCount
) {
}
