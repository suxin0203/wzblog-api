package com.wzblog.modules.interaction.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 评论/留言作者视图：登录用户带 userId，游客仅昵称。
 * userId 序列化为字符串（契约 ID 全局 string，裁决 B-1）。
 */
public record AuthorVO(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String nickname, String avatarKey, boolean guest) {

    public static AuthorVO guest(String nickname) {
        return new AuthorVO(null, nickname == null || nickname.isBlank() ? "匿名" : nickname, null,
                true);
    }
}
