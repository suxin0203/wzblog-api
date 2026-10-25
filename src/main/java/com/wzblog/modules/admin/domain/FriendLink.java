package com.wzblog.modules.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * friend_links 表实体。
 */
@Getter
@Setter
@TableName("friend_links")
public class FriendLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String blogName;
    private String blogUrl;
    private String theme;
    private String bloggerName;
    private String contact;
    private String logoKey;
    private Integer sortOrder;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
