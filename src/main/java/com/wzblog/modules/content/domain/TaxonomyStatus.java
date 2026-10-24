package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 分类/标签启用状态（00 §6.1 CategoryStatus / TagStatus）。
 */
public enum TaxonomyStatus {

    ENABLED("ENABLED"),
    DISABLED("DISABLED");

    @EnumValue
    private final String value;

    TaxonomyStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}