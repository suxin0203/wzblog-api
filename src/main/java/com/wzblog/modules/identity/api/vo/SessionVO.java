package com.wzblog.modules.identity.api.vo;

import java.time.Instant;

/**
 * 设备会话；{@code sessionId} 即 refresh token family_id。
 */
public record SessionVO(String sessionId, String ip, String userAgent,
                        Instant createdAt, Instant issuedAt, Instant expiresAt) {
}