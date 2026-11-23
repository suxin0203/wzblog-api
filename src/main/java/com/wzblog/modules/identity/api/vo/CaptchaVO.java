package com.wzblog.modules.identity.api.vo;

/**
 * GET /auth/captcha 响应。
 *
 * <p>{@code code} 为本地调试便利字段：仅当 {@code wzblog.auth.log-verification-code=true}
 * （WZBLOG_LOG_VERIFICATION_CODE，生产恒为 false）时返回明文验证码，
 * 供契约工具（tools/contract）等自动化登录使用；正常部署恒为 {@code null}。</p>
 */
public record CaptchaVO(String captchaId, String imageBase64, int expiresIn, String code) {
}
