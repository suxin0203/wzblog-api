package com.wzblog.modules.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ForgotResetRequest(

        @NotBlank(message = "resetToken 不能为空")
        String resetToken,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$",
                message = "密码需为 8~64 位可见 ASCII 且同时包含字母和数字")
        String newPassword
) {
}