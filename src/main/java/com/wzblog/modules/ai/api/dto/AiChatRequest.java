package com.wzblog.modules.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 */
public record AiChatRequest(@NotBlank @Size(max = AiChatRequest.HARD_MESSAGE_LIMIT) String message,
                            @Size(max = 26) String sessionId) {

    /** 单条提问业务上限（字符数，按 code point 计）。 */
    public static final int MAX_MESSAGE_CHARS = 600;

    /** Bean Validation 防爆硬上限（业务上限 ×10），超限统一 400 COMMON_VALIDATION_ERROR。 */
    public static final int HARD_MESSAGE_LIMIT = MAX_MESSAGE_CHARS * 10;
}
