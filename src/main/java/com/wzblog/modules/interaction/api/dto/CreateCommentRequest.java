package com.wzblog.modules.interaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表评论请求（M3 §4.1）。
 */
public record CreateCommentRequest(
        @NotBlank @Size(max = 20000) String content,
        String parentId,
        @Size(max = 30) String guestNickname) {
}
