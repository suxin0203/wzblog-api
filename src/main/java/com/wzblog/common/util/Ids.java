package com.wzblog.common.util;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字符串 ID 解析：入参按字符串接收并校验为正整数（00 §5）。
 */
public final class Ids {

    private Ids() {
    }

    public static Long parseNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parse(value);
    }

    public static Long parse(String value) {
        try {
            long id = Long.parseLong(value.trim());
            if (id <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return id;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "ID 必须为正整数", null);
        }
    }

    public static List<Long> parseList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                ids.add(parse(value));
            }
        }
        return new ArrayList<>(ids);
    }
}
