package com.wzblog.modules.media.api.vo;

import java.time.Instant;

/**
 * 文件视图（M5 §4.2）。
 */
public record FileVO(String fileId, String objectKey, String status, Instant createdAt,
                     String mimeType, Long sizeBytes, Integer width, Integer height,
                     String publicUrl, String thumbUrl) {

    public static FileVO empty() {
        return new FileVO(null, null, null, null, null, null, null, null, null, null);
    }
}
