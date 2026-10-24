package com.wzblog.common.api;

import java.util.List;

/**
 * 偏移分页响应体（04 §4）。
 * <p>{@code total} 是计数而非 ID，保持数字输出；{@code list} 永不为 null。</p>
 */
public record PageResult<T>(List<T> list, long total, int page, int pageSize, boolean hasNext) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        List<T> safeList = list == null ? List.of() : list;
        return new PageResult<>(safeList, total, page, pageSize, (long) page * pageSize < total);
    }

    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize, false);
    }
}