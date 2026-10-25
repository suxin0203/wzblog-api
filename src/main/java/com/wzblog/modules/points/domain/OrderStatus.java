package com.wzblog.modules.points.domain;

/**
 * 订单状态（六态）。
 */
public enum OrderStatus {
    PENDING,
    APPROVED,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REJECTED
}
