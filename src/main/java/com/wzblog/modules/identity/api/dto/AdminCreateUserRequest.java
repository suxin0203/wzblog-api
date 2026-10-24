package com.wzblog.modules.identity.api.dto;

import com.wzblog.common.security.Role;
import com.wzblog.modules.identity.domain.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(

        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^(?!deleted_)[A-Za-z0-9_]{4,20}$",
                message = "用户名需为 4~20 位字母、数字或下划线，且不得以 deleted_ 开头")
        String username,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$",
                message = "密码需为 8~64 位可见 ASCII 且同时包含字母和数字")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(max = 30, message = "昵称长度需为 1~30 个字符")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 150, message = "邮箱长度不能超过 150")
        String email,

        Role role,

        UserStatus status
) {
}