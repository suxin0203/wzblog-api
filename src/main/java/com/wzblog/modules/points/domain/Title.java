package com.wzblog.modules.points.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * titles 表实体：称号定义。
 */
@Getter
@Setter
@TableName("titles")
public class Title {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String cssClass;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
