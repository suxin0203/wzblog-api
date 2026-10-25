package com.wzblog.modules.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ai_feedbacks 表实体。
 */
@Getter
@Setter
@TableName("ai_feedbacks")
public class AiFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Long userId;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
}
