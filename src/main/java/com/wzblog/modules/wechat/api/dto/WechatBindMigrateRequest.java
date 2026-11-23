package com.wzblog.modules.wechat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/wechat/bind-migrate 请求（B18 可选增强）：扫码登录落在自动账号上时，
 * 凭已有博客账号的账密把 openid 移交过去（自动账号匿名化注销、评论改挂目标账号）。
 * 用户名/密码规则与 /auth/login 一致；防爆破复用登录的失败锁定 + 图形验证码。
 */
public record WechatBindMigrateRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(max = 20, message = "用户名长度不能超过 20")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码长度不能超过 64")
        String password,

        String captchaId,

        String captchaCode
) {
}
