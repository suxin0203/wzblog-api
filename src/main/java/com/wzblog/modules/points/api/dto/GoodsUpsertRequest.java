package com.wzblog.modules.points.api.dto;

import com.wzblog.modules.points.domain.GoodsStatus;
import com.wzblog.modules.points.domain.GoodsType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 商品创建/全量更新请求。
 */
public record GoodsUpsertRequest(
        @NotNull @Size(max = 100) String name,
        @NotNull GoodsType type,
        Long titleId,
        @Size(max = 500) String description,
        @Size(max = 255) String imageKey,
        @NotNull @Min(1) @Max(100000) Integer pointsPrice,
        @Min(0) @Max(999999) Integer stock,
        GoodsStatus status,
        @Min(-100000) @Max(100000) Integer sortOrder) {
}
