package com.wzblog.modules.content.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 阅读上报请求；source 缺省时按 Referer 判定（M2 §9）。
 */
public record ViewRequest(
        @Pattern(regexp = "internal|external", message = "source 只能是 internal 或 external")
        String source
) {
}
