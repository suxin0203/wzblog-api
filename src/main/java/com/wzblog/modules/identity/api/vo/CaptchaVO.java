package com.wzblog.modules.identity.api.vo;

public record CaptchaVO(String captchaId, String imageBase64, int expiresIn) {
}