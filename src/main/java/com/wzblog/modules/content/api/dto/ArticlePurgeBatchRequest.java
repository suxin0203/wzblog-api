package com.wzblog.modules.content.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 回收站批量彻底删除入参：文章 id 白名单。
 * 业务上界 100/批（R6：DTO 上界 = 业务上界 × 10 防爆）。
 */
public record ArticlePurgeBatchRequest(
        @NotEmpty @Size(max = 1000) List<Long> ids) {
}
