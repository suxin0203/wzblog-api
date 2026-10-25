package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 摘要请求。
 */
public record AiSummaryRequest(@Size(max = 16) String style,
                               @Min(80) @Max(300) Integer length) {
}
