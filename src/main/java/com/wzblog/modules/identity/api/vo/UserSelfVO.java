package com.wzblog.modules.identity.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * 本人资料：仅 /users/me 返回 email/status。
 */
public record UserSelfVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String nickname,
        String email,
        boolean emailVerified,
        String avatarUrl,
        String bio,
        String role,
        String status,
        TitleBriefVO activeTitle,
        Instant lastLoginAt,
        Instant createdAt
) {
}