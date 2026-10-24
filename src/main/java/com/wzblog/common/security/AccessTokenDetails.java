package com.wzblog.common.security;

import java.time.Instant;

/**
 * 当前请求所用 access token 的附加信息（登出黑名单需要 jti 与剩余有效期）。
 */
public record AccessTokenDetails(String jti, Instant expiresAt) {
}