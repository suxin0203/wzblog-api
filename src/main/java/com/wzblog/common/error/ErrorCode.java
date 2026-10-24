package com.wzblog.common.error;

/**
 * 错误码目录，与《04-接口契约》§7 逐字对齐；禁止自造未登记码。
 */
public enum ErrorCode {

    OK("OK", 200, "success"),

    COMMON_VALIDATION_ERROR("COMMON_VALIDATION_ERROR", 400, "参数校验失败"),
    COMMON_NOT_FOUND("COMMON_NOT_FOUND", 404, "资源不存在"),
    COMMON_IDEMPOTENCY_CONFLICT("COMMON_IDEMPOTENCY_CONFLICT", 409, "幂等键冲突"),
    COMMON_RATE_LIMITED("COMMON_RATE_LIMITED", 429, "请求过于频繁"),
    COMMON_INTERNAL_ERROR("COMMON_INTERNAL_ERROR", 500, "服务器内部错误"),

    AUTH_CAPTCHA_INVALID("AUTH_CAPTCHA_INVALID", 400, "验证码错误或已失效"),
    AUTH_CAPTCHA_EXPIRED("AUTH_CAPTCHA_EXPIRED", 400, "验证码已过期，请刷新"),
    AUTH_BAD_CREDENTIALS("AUTH_BAD_CREDENTIALS", 422, "用户名或密码错误"),
    AUTH_ACCOUNT_LOCKED("AUTH_ACCOUNT_LOCKED", 429, "登录失败次数过多，请稍后再试"),
    AUTH_ACCOUNT_DISABLED("AUTH_ACCOUNT_DISABLED", 403, "账号已停用，请联系管理员"),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", 401, "登录状态无效，请重新登录"),
    AUTH_TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", 401, "登录状态已过期"),
    AUTH_REFRESH_INVALID("AUTH_REFRESH_INVALID", 401, "登录已失效，请重新登录"),
    AUTH_REFRESH_REUSED("AUTH_REFRESH_REUSED", 401, "登录凭证已失效，请重新登录"),
    AUTH_REFRESH_CONFLICT("AUTH_REFRESH_CONFLICT", 409, "刷新冲突，请稍后重试"),
    AUTH_VERIFICATION_CODE_INVALID("AUTH_VERIFICATION_CODE_INVALID", 400, "验证码错误"),
    AUTH_VERIFICATION_CODE_EXPIRED("AUTH_VERIFICATION_CODE_EXPIRED", 400, "验证码已过期，请重新获取"),
    AUTH_VERIFICATION_CODE_LOCKED("AUTH_VERIFICATION_CODE_LOCKED", 429, "验证码错误次数过多，请重新获取"),
    AUTH_RESET_TOKEN_EXPIRED("AUTH_RESET_TOKEN_EXPIRED", 410, "重置凭证已失效，请重新获取验证码"),
    AUTH_OLD_PASSWORD_MISMATCH("AUTH_OLD_PASSWORD_MISMATCH", 422, "原密码错误"),
    AUTH_PASSWORD_UNCHANGED("AUTH_PASSWORD_UNCHANGED", 422, "新密码不能与原密码相同"),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", 403, "当前角色无权限进行此操作"),
    AUTH_UNAUTHORIZED("AUTH_UNAUTHORIZED", 401, "请先登录"),
    AUTH_USERNAME_TAKEN("AUTH_USERNAME_TAKEN", 409, "用户名已被占用"),

    USER_USERNAME_TAKEN("USER_USERNAME_TAKEN", 409, "用户名已被占用"),
    USER_EMAIL_TAKEN("USER_EMAIL_TAKEN", 409, "邮箱已被使用"),
    USER_NOT_FOUND("USER_NOT_FOUND", 404, "用户不存在"),
    USER_SESSION_NOT_FOUND("USER_SESSION_NOT_FOUND", 404, "会话不存在或已失效"),
    USER_CANNOT_DELETE_SELF("USER_CANNOT_DELETE_SELF", 422, "不能注销当前登录账号"),
    USER_LAST_ADMIN("USER_LAST_ADMIN", 422, "系统至少保留一个可用的管理员");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}