package com.wzblog.modules.points.api.vo;

import java.time.Instant;
import java.util.List;

/**
 * 积分账户快照（含称号）。
 */
public record PointsAccountVO(String userId, int balance, int totalEarned, int totalSpent,
                              Instant updatedAt, List<TitleItemVO> titles,
                              TitleItemVO activeTitle) {
}
