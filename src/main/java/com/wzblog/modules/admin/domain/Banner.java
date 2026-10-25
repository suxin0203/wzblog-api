package com.wzblog.modules.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * banners 表实体。
 */
@Getter
@Setter
@TableName("banners")
public class Banner {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String imageKey;
    private String linkUrl;
    private Integer sortOrder;
    private String status;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
