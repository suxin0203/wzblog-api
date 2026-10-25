package com.wzblog.modules.content.app.port;

/**
 * 跨模块只读的分类/标签摘要（M2 §16.1 端口出参，不暴露 api VO）。
 */
public record TaxonomyBrief(String name, String slug, long articleCount) {
}
