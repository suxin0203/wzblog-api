package com.wzblog.modules.points.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * points_ledger 表实体：只追加的积分流水。
 */
@Getter
@Setter
@TableName("points_ledger")
public class LedgerEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer amount;
    private Integer balanceAfter;
    private String type;
    private String bizType;
    private String bizId;
    private String idempotencyKey;
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
