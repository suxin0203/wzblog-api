package com.wzblog.modules.identity.api.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        // 契约 UpdateProfileRequest.required 为空：昵称可选；缺省/空白时服务层保持原值
        @Size(max = 30, message = "昵称长度需为 1~30 个字符")
        String nickname,

        @Size(max = 255, message = "个人简介长度不能超过 255")
        String bio,

        @Size(max = 255, message = "头像 key 长度不能超过 255")
        String avatarKey
) {
}