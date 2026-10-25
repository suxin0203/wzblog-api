package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 标签建议请求。
 */
public record AiTagsRequest(@Min(1) @Max(8) Integer count) {
}
