package com.wzblog.modules.content.api.vo;

import java.util.List;

/**
 * 搜索单页结果；{@code degraded=true} 表示关键词不足 2 字、已降级 LIKE。
 */
public record SearchResultVO(List<ArticleListItemVO> list, boolean degraded) {
}
