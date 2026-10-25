package com.wzblog.modules.media.api.vo;

import java.time.Instant;

/**
 * 后台文件视图（M5 §4.2）：FileVO 全字段 + 管理信息。
 */
public record AdminFileVO(String fileId, String objectKey, String status, Instant createdAt,
                          String mimeType, Long sizeBytes, Integer width, Integer height,
                          String publicUrl, String thumbUrl,
                          String originalName, String sha256, String uploaderId,
                          String bizType, Integer refCount, String thumbKey, Instant deletedAt) {
}
