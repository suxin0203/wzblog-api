package com.wzblog.modules.interaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * guestbook_messages 表实体。
 */
@Getter
@Setter
@TableName("guestbook_messages")
public class GuestbookMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String nickname;
    private String content;
    private CommentStatus status;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
