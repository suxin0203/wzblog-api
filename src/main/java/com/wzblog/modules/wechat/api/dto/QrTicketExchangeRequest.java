package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/qr/tickets/exchange 请求（M8 §4.1）：ticket 43 字符 Base64URL，一次性。
 */
public record QrTicketExchangeRequest(

        @NotBlank(message = "ticket 不能为空")
        @Size(min = 43, max = 43, message = "ticket 格式不正确")
        String ticket
) {
}
