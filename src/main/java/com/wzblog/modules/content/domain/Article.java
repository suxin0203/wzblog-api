package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * articles 表实体（V1__schema.sql）。content_md 是唯一事实源，其余正文列为派生列。
 * <p>不使用全局逻辑删除：所有查询显式过滤 {@code deleted_at}。</p>
 */
@Getter
@Setter
@TableName("articles")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private Long categoryId;
    private String title;
    private String slug;
    private String summary;
    private String coverKey;
    private String contentMd;
    private String contentHtml;
    private String contentText;
    private String tocJson;
    private Integer wordCount;
    private Integer readingSeconds;
    private ArticleStatus status;
    private ArticleVisibility visibility;
    private Boolean isPinned;
    private Boolean allowComment;
    private Integer viewCount;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer commentCount;
    private Integer viewInternalCount;
    private Integer viewExternalCount;
    private LocalDateTime publishedAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}