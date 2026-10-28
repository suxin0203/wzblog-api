package com.wzblog.common.api;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

/**
 * offset 分页参数统一校验（契约 §5.1：越界 400，不静默收敛）。
 * null 视为未传，取默认值；传了就必须合法。
 */
public final class PageParams {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageParams() {
    }

    public static int requirePage(Integer page) {
        int value = page == null ? DEFAULT_PAGE : page;
        if (value < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "page 必须大于等于 1", null);
        }
        return value;
    }

    public static int requirePageSize(Integer pageSize) {
        int value = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "pageSize 取值范围为 1~100", null);
        }
        return value;
    }
}
