package com.wzblog.modules.points.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * points_accounts 表实体：余额快照，真相在流水（ADR-07）。
 */
@Getter
@Setter
@TableName("points_accounts")
public class PointsAccount {

    @TableId
    private Long userId;
    private Integer balance;
    private Integer totalEarned;
    private Integer totalSpent;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
