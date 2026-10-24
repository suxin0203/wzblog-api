package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 验证码场景（00 §6.1）。本期只使用 {@code RESET}。
 */
public enum VerificationScene {

    REGISTER("REGISTER"),
    RESET("RESET"),
    BIND("BIND");

    @EnumValue
    private final String value;

    VerificationScene(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}