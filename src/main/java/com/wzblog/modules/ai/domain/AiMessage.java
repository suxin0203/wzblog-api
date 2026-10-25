package com.wzblog.modules.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ai_messages 表实体：消息与工具轨迹。
 */
@Getter
@Setter
@TableName("ai_messages")
public class AiMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_TOOL = "TOOL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private String status;
    private String toolName;
    private String toolCallId;
    private String toolArgsJson;
    private String toolResultJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
