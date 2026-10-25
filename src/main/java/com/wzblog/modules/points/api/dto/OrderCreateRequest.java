package com.wzblog.modules.points.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 下单请求（M4 §4.1）。
 */
public record OrderCreateRequest(
        @NotNull Long goodsId,
        @Min(1) @Max(10) Integer quantity,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String userRemark) {
}
