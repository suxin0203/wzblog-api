package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 反馈请求。
 */
public record AiFeedbackRequest(@NotNull Integer rating,
                                @Size(max = 500) String comment) {
}
