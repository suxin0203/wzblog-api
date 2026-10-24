package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * categories 表实体；物理删除，无 deleted_at（V1__schema.sql）。
 */
@Getter
@Setter
@TableName("categories")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String slug;
    private String description;
    private Integer sortOrder;
    private TaxonomyStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}