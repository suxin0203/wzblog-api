package com.wzblog.modules.identity.api.dto;

/**
 * 刷新令牌请求；格式不合法（含缺失）按 {@code AUTH_REFRESH_INVALID} 处理，而非参数校验错误。
 */
public record RefreshRequest(String refreshToken) {
}