package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * article_revisions 表实体；每篇保留最近 20 版。
 */
@Getter
@Setter
@TableName("article_revisions")
public class ArticleRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Integer revisionNo;
    private Long editorId;
    private String title;
    private String summary;
    private String contentMd;
    private String changeNote;
    private LocalDateTime createdAt;
}