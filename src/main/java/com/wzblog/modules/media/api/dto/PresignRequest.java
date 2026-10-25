package com.wzblog.modules.media.api.dto;

import com.wzblog.common.media.FileBizType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * POST /files/presign 请求（M5 §4.1）。
 */
public record PresignRequest(
        @NotBlank String filename,
        @NotBlank String contentType,
        @NotNull @Positive Long size,
        @NotNull FileBizType bizType) {
}
