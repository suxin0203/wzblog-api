package com.wzblog.modules.interaction.api.vo;

/**
 * 评论/留言作者视图：登录用户带 userId，游客仅昵称。
 */
public record AuthorVO(Long userId, String nickname, String avatarKey, boolean guest) {

    public static AuthorVO guest(String nickname) {
        return new AuthorVO(null, nickname == null || nickname.isBlank() ? "匿名" : nickname, null,
                true);
    }
}
