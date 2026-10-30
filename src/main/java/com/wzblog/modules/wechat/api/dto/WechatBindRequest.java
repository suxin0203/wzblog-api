package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/wechat/bind 请求（M8 §4.1）：code 必填；绑定目标恒为当前登录用户。
 */
public record WechatBindRequest(

        @NotBlank(message = "code 不能为空")
        @Size(max = 128, message = "code 长度不能超过 128")
        String code
) {
}
