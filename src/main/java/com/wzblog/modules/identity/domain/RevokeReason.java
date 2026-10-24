package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * Refresh Token 吊销原因（M1 §6.5）。
 */
public enum RevokeReason {

    ROTATED("ROTATED"),
    LOGOUT("LOGOUT"),
    LOGOUT_ALL("LOGOUT_ALL"),
    REUSE_DETECTED("REUSE_DETECTED"),
    ADMIN("ADMIN");

    @EnumValue
    private final String value;

    RevokeReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}