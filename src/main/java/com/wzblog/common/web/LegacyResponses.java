package com.wzblog.common.web;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧协议响应壳工具（兼容已上线小程序）：
 * HTTP 恒 200、数字 code、token 在顶层、user 对象 snake_case。
 * 小程序 request.js 的判定：code===200 成功 / code===0 业务失败 / 无 code 字段直接取 data。
 */
public final class LegacyResponses {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LegacyResponses() {
    }

    public static Map<String, Object> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    public static Map<String, Object> okWithTokens(String message, String token,
                                                   String refreshToken, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        if (token != null) {
            body.put("token", token);
        }
        if (refreshToken != null) {
            body.put("refreshToken", refreshToken);
        }
        body.put("data", data);
        return body;
    }

    /** 无 code 字段的响应（旧 /wechat/openid 成功格式：{message, data}，request.js 走"格式3"分支）。 */
    public static Map<String, Object> raw(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    public static Map<String, Object> fail(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return body;
    }

    /** 旧格式 user（snake_case；调用方自行决定放 data 还是顶层）。 */
    public static Map<String, Object> legacyUser(Long id, String username, String email,
                                                 String nickname, String avatarKey,
                                                 String avatarUrl, String role, int isRoot,
                                                 String status, String bio,
                                                 LocalDateTime createdAt, LocalDateTime updatedAt,
                                                 LocalDateTime lastLoginAt) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("username", username);
        user.put("email", email);
        user.put("nickname", nickname);
        user.put("avatar_key", avatarKey);
        user.put("avatar_url", avatarUrl);
        user.put("bio", bio);
        user.put("role", role);
        user.put("is_root", isRoot);
        user.put("status", status);
        user.put("created_at", format(createdAt));
        user.put("updated_at", format(updatedAt));
        user.put("last_login_at", format(lastLoginAt));
        return user;
    }

    public static String format(LocalDateTime utc) {
        return utc == null ? null : TIME.format(utc);
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
