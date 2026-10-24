package com.wzblog.common.error;

public enum ErrorCode {

    OK("OK", 200, "success"),

    COMMON_VALIDATION_ERROR("COMMON_VALIDATION_ERROR", 400, "参数校验失败"),
    COMMON_NOT_FOUND("COMMON_NOT_FOUND", 404, "资源不存在"),
    COMMON_IDEMPOTENCY_CONFLICT("COMMON_IDEMPOTENCY_CONFLICT", 409, "幂等键冲突"),
    COMMON_RATE_LIMITED("COMMON_RATE_LIMITED", 429, "请求过于频繁"),
    COMMON_INTERNAL_ERROR("COMMON_INTERNAL_ERROR", 500, "服务器内部错误"),

    AUTH_UNAUTHORIZED("AUTH_UNAUTHORIZED", 401, "请先登录"),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", 401, "登录状态无效，请重新登录"),
    AUTH_TOKEN_EXPIRED("AUTH_TOKEN_EXPIRED", 401, "登录状态已过期"),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", 403, "当前角色无权限进行此操作"),
    AUTH_ACCOUNT_DISABLED("AUTH_ACCOUNT_DISABLED", 403, "账号已停用，请联系管理员");

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