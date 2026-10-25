package com.wzblog.modules.points.domain.event;

/**
 * 管理员调分事件（M4 → M3 通知）。
 */
public record PointsAdjustedEvent(Long ledgerId, Long userId, int amount, int balanceAfter,
                                  String reason) {
}
