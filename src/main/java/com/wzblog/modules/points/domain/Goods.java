package com.wzblog.modules.points.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * points_goods 表实体。
 */
@Getter
@Setter
@TableName("points_goods")
public class Goods {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private Long titleId;
    private String description;
    private String imageKey;
    private Integer pointsPrice;
    private Integer stock;
    private Integer soldCount;
    private String status;
    private Integer sortOrder;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
