package com.wzblog.modules.identity.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record TitleBriefVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String name,
        String cssClass
) {
}