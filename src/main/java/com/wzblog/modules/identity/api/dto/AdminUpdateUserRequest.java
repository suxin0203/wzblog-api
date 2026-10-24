package com.wzblog.modules.identity.api.dto;

import com.wzblog.common.security.Role;
import com.wzblog.modules.identity.domain.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUpdateUserRequest(

        @NotBlank(message = "昵称不能为空")
        @Size(max = 30, message = "昵称长度需为 1~30 个字符")
        String nickname,

        @NotNull(message = "角色不能为空")
        Role role,

        @NotNull(message = "状态不能为空")
        UserStatus status,

        @Email(message = "邮箱格式不正确")
        @Size(max = 150, message = "邮箱长度不能超过 150")
        String email,

        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,64}$",
                message = "密码需为 8~64 位可见 ASCII 且同时包含字母和数字")
        String newPassword
) {
}