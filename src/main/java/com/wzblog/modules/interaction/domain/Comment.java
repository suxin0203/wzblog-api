package com.wzblog.modules.interaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * comments 表实体：两级评论（根 root_id 为 NULL；回复平铺在根下）。
 */
@Getter
@Setter
@TableName("comments")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long userId;
    private String guestName;
    private Long rootId;
    private Long parentId;
    private Long replyToUserId;
    private String content;
    private CommentStatus status;
    private Integer likeCount;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
