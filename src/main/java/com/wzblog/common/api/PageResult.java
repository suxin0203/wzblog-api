package com.wzblog.common.api;

import java.util.List;

/**
 * 偏移分页响应体（04 §4）。
 * <p>{@code total} 是计数而非 ID，保持数字输出；{@code list} 永不为 null。</p>
 *
 * <p>【教学】偏移（offset）分页 = 传统"第 page 页、每页 pageSize 条、共 total 条"模型，
 * 配合 {@link PageParams} 做入参校验。适用场景：管理后台、需要页码跳转的列表。
 * 公开信息流请改用 {@link CursorPage}（游标分页），理由见其类注释。</p>
 *
 * <p>total 保持数字类型（而非像其他 ID 一样转字符串）的说明：它不是资源 ID，
 * 不存在 JS Number 53 位精度丢失问题（计数远到不了 2^53），数字对前端更方便运算。</p>
 */
public record PageResult<T>(List<T> list, long total, int page, int pageSize, boolean hasNext) {

    /**
     * 标准构造入口。
     * hasNext 由公式推导而非让调用方自己算：{@code page*pageSize < total}，
     * 避免各处重复实现造成口径不一致（例如最后一页恰好整页时也必须正确返回 false）。
     * 注意 (long) 强转：page*pageSize 是 int 乘法，大页码会溢出成负数导致误判。
     */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        List<T> safeList = list == null ? List.of() : list;
        return new PageResult<>(safeList, total, page, pageSize, (long) page * pageSize < total);
    }

    /** 空结果页（查询无数据时的快捷构造）。 */
    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize, false);
    }
}
