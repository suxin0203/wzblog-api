package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/wechat/login 请求（M8 §4.1）：code 必填，nickname/avatarKey 可选。
 */
public record WechatLoginRequest(

        @NotBlank(message = "code 不能为空")
        @Size(max = 128, message = "code 长度不能超过 128")
        String code,

        @Size(max = 30, message = "昵称长度不能超过 30")
        String nickname,

        @Size(max = 255, message = "头像 key 长度不能超过 255")
        @Pattern(regexp = "^[0-9A-Za-z][0-9A-Za-z/_.-]*$", message = "头像 key 格式不正确")
        String avatarKey
) {
}
