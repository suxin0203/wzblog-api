package com.wzblog.modules.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ai_sessions 表实体：26 位 ULID 主键，游客 user_id 为 NULL。
 */
@Getter
@Setter
@TableName("ai_sessions")
public class AiSession {

    @TableId
    private String id;
    private Long userId;
    private String channel;
    private String title;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
