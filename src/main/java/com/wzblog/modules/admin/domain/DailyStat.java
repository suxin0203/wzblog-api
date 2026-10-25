package com.wzblog.modules.admin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * daily_stats 表实体：站点日统计（预聚合，Asia/Shanghai 自然日）。
 */
@Getter
@Setter
@TableName("daily_stats")
public class DailyStat {

    @TableId
    private LocalDate statDate;
    private Integer userRegistered;
    private Integer articlePublished;
    private Integer commentCreated;
    private Integer viewCount;
    private Integer viewInternalCount;
    private Integer viewExternalCount;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer aiMessageCount;
    private Integer pointsIssued;
    private Integer pointsConsumed;
    private LocalDateTime updatedAt;
}
