package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /auth/qr/sessions/{sceneId}/confirm 请求（M8 §4.1）。
 * bindToken 仅在无小程序登录态时必填；userInfo 按契约保留（仅 register-and-confirm 生效，本接口忽略）。
 */
public record QrConfirmRequest(

        @Size(min = 43, max = 43, message = "bindToken 格式不正确")
        String bindToken,

        UserInfo userInfo
) {

    public record UserInfo(

            @Size(max = 30, message = "昵称长度不能超过 30")
            String nickname
    ) {
    }
}
