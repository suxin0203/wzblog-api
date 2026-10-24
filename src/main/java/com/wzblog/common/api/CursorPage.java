package com.wzblog.common.api;

import java.util.List;

/**
 * 游标分页响应体（00 §5.4）：{@code list} 永不为 null，{@code nextCursor} 为空表示到底。
 */
public record CursorPage<T>(List<T> list, String nextCursor, boolean hasNext) {

    public static <T> CursorPage<T> of(List<T> list, String nextCursor, boolean hasNext) {
        return new CursorPage<>(list == null ? List.of() : list, nextCursor, hasNext);
    }
}