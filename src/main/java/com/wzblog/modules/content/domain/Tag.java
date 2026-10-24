package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * tags 表实体；物理删除（V1__schema.sql）。
 */
@Getter
@Setter
@TableName("tags")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String slug;
    private TaxonomyStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}