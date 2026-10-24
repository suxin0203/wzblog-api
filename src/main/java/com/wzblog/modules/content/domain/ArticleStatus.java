package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 文章状态（00 §6.1）。
 */
public enum ArticleStatus {

    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    ARCHIVED("ARCHIVED");

    @EnumValue
    private final String value;

    ArticleStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}