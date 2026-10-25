package com.wzblog.modules.points.api.dto;

import com.wzblog.modules.points.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 后台流转请求。
 */
public record OrderTransitionRequest(
        @NotNull OrderStatus toStatus,
        @Size(max = 60) String logisticsCompany,
        @Size(max = 70) String logisticsNo,
        @Size(max = 255) String adminRemark) {
}
