package com.wzblog.modules.wechat.api.vo;

/**
 * 用户简版视图（M8 §4.2 UserBriefVO）：openid 明文永不输出，openidBound 仅表示"是否已绑定"。
 */
public record WechatUserVO(String id, String username, String nickname, String avatarKey,
                           String role, String status, boolean openidBound) {

    public static WechatUserVO from(Long id, String username, String nickname, String avatarKey,
                                    String role, String status, boolean openidBound) {
        return new WechatUserVO(id == null ? null : String.valueOf(id), username, nickname,
                avatarKey, role, status, openidBound);
    }
}
