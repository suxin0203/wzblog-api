package com.wzblog.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端信息（IP + User-Agent）。代理头仅在请求来自本机/环回地址时采信。
 */
public record ClientInfo(String ip, String userAgent) {

    private static final int IP_MAX = 45;
    private static final int UA_MAX = 255;

    public static ClientInfo from(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = remote;
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remote)) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                ip = first;
            }
        }
        return new ClientInfo(truncate(ip, IP_MAX), truncate(request.getHeader("User-Agent"), UA_MAX));
    }

    public static ClientInfo of(String ip, String userAgent) {
        return new ClientInfo(truncate(ip, IP_MAX), truncate(userAgent, UA_MAX));
    }

    private static boolean isTrustedProxy(String remote) {
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}