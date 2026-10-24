package com.wzblog.modules.identity.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

/**
 * 公开资料白名单：id/username/nickname/avatarUrl/bio/role/activeTitle/createdAt。
 */
public record PublicUserProfileVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String nickname,
        String avatarUrl,
        String bio,
        String role,
        TitleBriefVO activeTitle,
        Instant createdAt
) {
}