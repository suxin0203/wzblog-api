package com.wzblog.modules.interaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * notifications 表实体：dedupe_key 唯一键保证同一业务事件只落一条。
 */
@Getter
@Setter
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipientId;
    private Long actorId;
    private String type;
    private String title;
    private String content;
    private String linkType;
    private String linkId;
    private String dedupeKey;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
