package com.wzblog.modules.points.domain.event;

/**
 * 订单状态变更事件（M4 → M3 通知）。订单创建不发。
 */
public record OrderStatusChangedEvent(Long orderId, String orderNo, Long userId,
                                      String fromStatus, String toStatus) {
}
