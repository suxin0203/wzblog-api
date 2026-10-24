package com.wzblog.modules.identity.api.vo;

public record ResetTokenVO(String resetToken, int expiresIn) {
}