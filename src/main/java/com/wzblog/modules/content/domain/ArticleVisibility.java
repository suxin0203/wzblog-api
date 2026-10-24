package com.wzblog.modules.content.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 文章可见性（00 §6.1）。
 */
public enum ArticleVisibility {

    PUBLIC("PUBLIC"),
    PRIVATE("PRIVATE"),
    UNLISTED("UNLISTED");

    @EnumValue
    private final String value;

    ArticleVisibility(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}