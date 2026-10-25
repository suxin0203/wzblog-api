package com.wzblog.modules.media.api.vo;

import java.time.Instant;

/**
 * 预签名响应（M5 §4.2）。
 */
public record PresignVO(String fileId, String objectKey, String uploadUrl, Instant expiresAt) {
}
