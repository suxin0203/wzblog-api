package com.wzblog.modules.points.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 称号创建/更新请求。
 */
public record TitleUpsertRequest(
        @NotNull @Size(max = 50) String name,
        @Size(max = 50) String cssClass,
        @Size(max = 255) String description,
        @Min(-100000) @Max(100000) Integer sortOrder) {
}
