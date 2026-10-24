package com.wzblog.common.security;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 角色（00 §6.1）：{@code USER < EDITOR < ADMIN}。
 */
public enum Role {

    USER("USER"),
    EDITOR("EDITOR"),
    ADMIN("ADMIN");

    @EnumValue
    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Spring Security authority，统一 {@code ROLE_} 前缀。 */
    public String authority() {
        return "ROLE_" + name();
    }
}