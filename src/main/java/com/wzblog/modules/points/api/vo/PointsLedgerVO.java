package com.wzblog.modules.points.api.vo;

import java.time.Instant;

/**
 * 积分流水。
 */
public record PointsLedgerVO(String id, int amount, int balanceAfter, String type,
                             String bizType, String bizId, String remark, Instant createdAt) {
}
