package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/wechat/bind-by-token 请求：扫码 confirm 下发的一次性 bindToken（43 字符 Base64URL）。
 * 网页已登录用户凭它把该次扫码的 openid 绑定到当前登录账号（B18 可选增强；openid 属扫码
 * 自动账号时服务端自动匿名化注销并移交）。
 */
public record WechatBindByTokenRequest(

        @NotBlank(message = "bindToken 不能为空")
        @Size(min = 43, max = 43, message = "bindToken 格式不正确")
        String bindToken
) {
}
