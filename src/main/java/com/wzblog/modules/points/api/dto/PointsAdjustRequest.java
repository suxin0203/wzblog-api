package com.wzblog.modules.points.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员调分请求。
 */
public record PointsAdjustRequest(
        @NotNull @Min(-100000) @Max(100000) Integer amount,
        @NotNull @Size(max = 255) String reason) {
}
