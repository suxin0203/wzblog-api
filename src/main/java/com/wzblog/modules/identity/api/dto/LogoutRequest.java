package com.wzblog.modules.identity.api.dto;

/**
 * 登出请求；找不到或已吊销时幂等返回成功。
 */
public record LogoutRequest(String refreshToken) {
}