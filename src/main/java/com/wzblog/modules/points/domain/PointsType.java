package com.wzblog.modules.points.domain;

/**
 * 积分类型（00 §6.1，与 points_ledger.ck 约束一致）。
 */
public enum PointsType {
    DAILY_LOGIN,
    ARTICLE_PUBLISH,
    COMMENT_APPROVED,
    LIKE_RECEIVED,
    LIKE_REVERSED,
    ARTICLE_PUBLISH_REVERSAL,
    COMMENT_APPROVED_REVERSAL,
    REDEEM,
    REFUND,
    ADMIN_ADJUST
}
