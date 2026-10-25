package com.wzblog.modules.points.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * points_orders 表实体（含商品快照与状态时间）。
 */
@Getter
@Setter
@TableName("points_orders")
public class PointsOrder {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String idempotencyKey;
    private Long userId;
    private Long goodsId;
    private String goodsNameSnapshot;
    private String goodsTypeSnapshot;
    private Integer unitPoints;
    private Integer quantity;
    private Integer totalPoints;
    private String status;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private String logisticsCompany;
    private String logisticsNo;
    private String userRemark;
    private String adminRemark;
    private String cancelledReason;
    private LocalDateTime approvedAt;
    private LocalDateTime shippedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
