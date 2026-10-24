package com.wzblog.modules.identity.api.vo;

/**
 * 找回密码请求响应：无论邮箱是否存在都返回 sent=true（防枚举）。
 */
public record ForgotSentVO(boolean sent, int expiresIn) {
}