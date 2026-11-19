package com.wzblog.common.api;

import java.util.List;

/**
 * 游标分页响应体（00 §5.4）：{@code list} 永不为 null，{@code nextCursor} 为空表示到底。
 *
 * <p>【教学：什么时候用游标分页而不是 PageResult】</p>
 * <ul>
 *   <li>信息流/公开列表（首页文章、评论列表）→ 用本类。深翻页时 offset 分页的
 *       {@code LIMIT 10000,10} 要扫过前一万行；且新数据不断插入时 offset 会"错位"
 *       （漏看或重复）。游标 = "从上次最后一条之后继续"，稳定又快。</li>
 *   <li>管理台后台列表（需要"第 3 页/共 10 页"跳页）→ 用 {@link PageResult}。</li>
 * </ul>
 * <p>游标本体对客户端是不透明字符串（见 {@link CursorCodec}），内部格式想改就改，
 * 前端无感知 —— 这是"隐藏实现"带来的演进自由。两种分页在本项目并存，按场景选用。</p>
 *
 * <p>【设计细节】record + 静态工厂 {@link #of}：工厂里把 null list 兜底成空集合，
 * 保证前端永远可以放心地 {@code for (x of data.list)}，不需要判空。</p>
 */
public record CursorPage<T>(List<T> list, String nextCursor, boolean hasNext) {

    /**
     * 标准构造入口。
     * @param list      本页数据；传 null 会被替换为不可变空集合（防御式默认值）
     * @param nextCursor 下一页游标；null/空字符串表示没有更多数据
     * @param hasNext   是否还有下一页（与 nextCursor 是否为空保持一致，冗余字段方便前端判断）
     */
    public static <T> CursorPage<T> of(List<T> list, String nextCursor, boolean hasNext) {
        return new CursorPage<>(list == null ? List.of() : list, nextCursor, hasNext);
    }
}
