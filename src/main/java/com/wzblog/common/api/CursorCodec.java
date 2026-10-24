package com.wzblog.common.api;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 游标编解码：{@code Base64URL("{published_at_epoch_ms}:{id}")}（M2 §8）。
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public record Cursor(Instant publishedAt, long id) {
    }

    public static String encode(LocalDateTime publishedAt, long id) {
        long epochMillis = publishedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        String raw = epochMillis + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("bad cursor");
            }
            long epochMillis = Long.parseLong(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            if (id <= 0) {
                throw new IllegalArgumentException("bad cursor id");
            }
            return new Cursor(Instant.ofEpochMilli(epochMillis), id);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "cursor 非法", null);
        }
    }

    public static LocalDateTime toUtc(Cursor cursor) {
        return LocalDateTime.ofInstant(cursor.publishedAt(), ZoneOffset.UTC);
    }
}
