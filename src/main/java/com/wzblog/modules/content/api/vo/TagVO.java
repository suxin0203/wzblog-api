package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record TagVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name,
        String slug,
        String status,
        long articleCount
) {
}
