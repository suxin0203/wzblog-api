package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 */
public record AiChatRequest(@NotBlank @Size(max = AiChatRequest.MAX_MESSAGE_CHARS) String message,
                            @Size(max = 26) String sessionId) {

    /** 单条提问上限（字符数，按 code point 计）。 */
    public static final int MAX_MESSAGE_CHARS = 600;
}
