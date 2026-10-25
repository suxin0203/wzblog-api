package com.wzblog.modules.media.api.vo;

import java.time.Instant;

/**
 * 访问 URL 响应（M5 §4.2）。公开 URL 的 expiresAt 为 null。
 */
public record UrlVO(String fileId, String url, String thumbUrl, Instant expiresAt) {
}
