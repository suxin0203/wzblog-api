package com.wzblog.modules.identity.api.vo;

public record TokenPairVO(String tokenType, String accessToken, int expiresIn,
                          String refreshToken, int refreshExpiresIn) {

    public static TokenPairVO bearer(String accessToken, int expiresIn,
                                     String refreshToken, int refreshExpiresIn) {
        return new TokenPairVO("Bearer", accessToken, expiresIn, refreshToken, refreshExpiresIn);
    }
}