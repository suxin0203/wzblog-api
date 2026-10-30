package com.wzblog.modules.wechat.api.vo;

/**
 * POST /auth/wechat/bind 响应（M8 §4.2 WechatBindVO）：重复绑同一 openid 幂等返回 bound=true。
 */
public record WechatBindVO(boolean bound, WechatUserVO user) {
}
