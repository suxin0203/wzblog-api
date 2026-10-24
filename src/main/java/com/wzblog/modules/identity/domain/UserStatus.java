package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 用户状态（00 §6.1）。
 */
public enum UserStatus {

    ACTIVE("ACTIVE"),
    DISABLED("DISABLED");

    @EnumValue
    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}