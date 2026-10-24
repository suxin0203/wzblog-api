package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 注册来源（00 §6.1）。
 */
public enum UserSource {

    WEB("WEB"),
    WECHAT("WECHAT"),
    QR("QR");

    @EnumValue
    private final String value;

    UserSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}