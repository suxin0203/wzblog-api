package com.wzblog.modules.points.api.vo;

import java.time.Instant;
import java.util.List;

/**
 * 订单视图：列表掩码手机号，详情（withLogs）附日志与明文手机号。
 */
public record OrderVO(String orderNo, String userId, String goodsId, String goodsName,
                      String goodsType, int unitPoints, int quantity, int totalPoints,
                      String status, String receiverName, String receiverPhone,
                      String receiverAddress, String logisticsCompany, String logisticsNo,
                      String userRemark, String adminRemark, String cancelledReason,
                      Instant approvedAt, Instant shippedAt, Instant completedAt,
                      Instant cancelledAt, Instant createdAt, Instant updatedAt,
                      List<OrderLogVO> logs, String receiverPhonePlain) {

    public record OrderLogVO(String fromStatus, String toStatus, String operatorId,
                             String remark, Instant createdAt) {
    }
}
