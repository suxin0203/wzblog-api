package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 登录失败原因（M1 §6.1），写入 {@code login_audits.failure_reason}。
 */
public enum LoginFailureReason {

    CAPTCHA_INVALID("CAPTCHA_INVALID"),
    BAD_CREDENTIALS("BAD_CREDENTIALS"),
    DISABLED("DISABLED"),
    LOCKED("LOCKED"),
    RATE_LIMITED("RATE_LIMITED"),
    INTERNAL_ERROR("INTERNAL_ERROR");

    @EnumValue
    private final String value;

    LoginFailureReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}