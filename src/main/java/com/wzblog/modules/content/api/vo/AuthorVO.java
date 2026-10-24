package com.wzblog.modules.content.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 列表/详情中的作者摘要（经 M1 UserQueryService 批量装配）。
 */
public record AuthorVO(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String nickname,
        String avatarKey
) {
}
