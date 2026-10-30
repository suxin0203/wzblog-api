package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/qr/sessions/{sceneId}/register-and-confirm 请求（M8 §4.1）。
 * 用户名/密码规则与 M1 注册一致；密码 BCrypt 存储，禁止明文落日志。
 */
public record QrRegisterConfirmRequest(

        @NotBlank(message = "bindToken 不能为空")
        @Size(min = 43, max = 43, message = "bindToken 格式不正确")
        String bindToken,

        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^(?!deleted_)[A-Za-z0-9_]{4,20}$",
                message = "用户名须为 4~20 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$",
                message = "密码须为 8~64 位可见字符且同时包含字母与数字")
        String password,

        @Size(max = 30, message = "昵称长度不能超过 30")
        String nickname
) {
}
