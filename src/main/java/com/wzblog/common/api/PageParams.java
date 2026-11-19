package com.wzblog.common.api;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

/**
 * offset 分页参数统一校验（契约 §5.1：越界 400，不静默收敛）。
 * null 视为未传，取默认值；传了就必须合法。
 *
 * <p>【教学：无状态工具类的标准写法】</p>
 * <ul>
 *   <li>{@code final class} —— 禁止继承（防止子类改变行为语义）；</li>
 *   <li>私有构造器 —— 禁止 new（它没有状态，实例毫无意义）；</li>
 *   <li>全部静态方法 —— 调用方 {@code PageParams.requirePage(page)} 零依赖直接用。</li>
 * </ul>
 * <p>这个"final + 私有构造 + 静态方法"三件套是 Java 最小的模块化手段，
 * 本项目 {@code Ids}、{@code Times}、{@code CursorCodec} 等工具类全部沿用。</p>
 *
 * <p>【"越界 400，不静默收敛"的哲学】另一种常见做法是把非法值悄悄纠正（page&lt;1 当 1、
 * size&gt;100 当 100）。看似友好，实则危险：调用方的 bug 被掩盖，排查"为什么我的分页不对"
 * 时毫无线索。显式报错让契约 violation 在第一时间暴露 —— 含糊的宽容比明确的报错更危险。</p>
 */
public final class PageParams {

    /** 未传 page 时的默认值：第 1 页。 */
    public static final int DEFAULT_PAGE = 1;
    /** 未传 pageSize 时的默认值。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** pageSize 上限：防歂数据库被一次超大查询拖垮（也是契约的一部分）。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 私有构造：工具类禁止实例化。 */
    private PageParams() {
    }

    /**
     * 校验并归一化 page 参数。
     * @param page 前端传入的页码，null 视为未传
     * @return 合法的页码（≥1）
     * @throws BusinessException COMMON_VALIDATION_ERROR 当 page &lt; 1
     */
    public static int requirePage(Integer page) {
        int value = page == null ? DEFAULT_PAGE : page;
        if (value < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "page 必须大于等于 1", null);
        }
        return value;
    }

    /**
     * 校验并归一化 pageSize 参数。
     * @throws BusinessException COMMON_VALIDATION_ERROR 当 pageSize 不在 1~100 区间
     */
    public static int requirePageSize(Integer pageSize) {
        int value = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "pageSize 取值范围为 1~100", null);
        }
        return value;
    }
}
