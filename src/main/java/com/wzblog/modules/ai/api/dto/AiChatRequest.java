package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 */
public record AiChatRequest(@NotBlank @Size(max = 600) String message,
                            @Size(max = 26) String sessionId) {
}
