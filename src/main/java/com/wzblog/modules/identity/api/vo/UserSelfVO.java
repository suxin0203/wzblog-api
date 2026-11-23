package com.wzblog.modules.identity.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * 本人资料：仅 /users/me 返回 email/status。
 * openidBound 表示当前账号是否已绑定微信登录（B18 可选增强，前端据此决定是否提示绑定）。
 */
public record UserSelfVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String nickname,
        String email,
        boolean emailVerified,
        boolean openidBound,
        String avatarUrl,
        String bio,
        String role,
        String status,
        TitleBriefVO activeTitle,
        Instant lastLoginAt,
        Instant createdAt
) {
}