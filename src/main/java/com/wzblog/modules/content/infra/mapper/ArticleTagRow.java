package com.wzblog.modules.content.infra.mapper;

import lombok.Getter;
import lombok.Setter;

/**
 * 文章-标签批量查询的投影行（避免逐篇查询标签）。
 */
@Getter
@Setter
public class ArticleTagRow {

    private Long articleId;
    private Long tagId;
    private String tagName;
    private String tagSlug;
}