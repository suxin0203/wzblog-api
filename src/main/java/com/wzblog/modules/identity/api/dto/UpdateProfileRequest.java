package com.wzblog.modules.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @NotBlank(message = "昵称不能为空")
        @Size(max = 30, message = "昵称长度需为 1~30 个字符")
        String nickname,

        @Size(max = 255, message = "个人简介长度不能超过 255")
        String bio,

        @Size(max = 255, message = "头像 key 长度不能超过 255")
        String avatarKey
) {
}