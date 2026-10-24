package com.wzblog.modules.identity.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public record AdminUserVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String nickname,
        String email,
        String avatarUrl,
        String role,
        String status,
        Instant lastLoginAt,
        String lastLoginIp,
        Instant createdAt
) {
}