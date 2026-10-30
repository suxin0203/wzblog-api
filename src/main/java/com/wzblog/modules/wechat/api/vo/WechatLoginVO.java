package com.wzblog.modules.wechat.api.vo;

/**
 * POST /auth/wechat/login 与 POST /auth/qr/tickets/exchange 共用响应（M8 §4.2 TokenPairVO）。
 */
public record WechatLoginVO(String tokenType, String accessToken, int expiresIn,
                            String refreshToken, int refreshExpiresIn, WechatUserVO user) {

    public static WechatLoginVO bearer(String accessToken, int expiresIn, String refreshToken,
                                       int refreshExpiresIn, WechatUserVO user) {
        return new WechatLoginVO("Bearer", accessToken, expiresIn, refreshToken,
                refreshExpiresIn, user);
    }
}
