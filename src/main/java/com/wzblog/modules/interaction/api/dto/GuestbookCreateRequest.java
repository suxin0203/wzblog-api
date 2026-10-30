package com.wzblog.modules.interaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表留言请求。
 */
public record GuestbookCreateRequest(
        @NotBlank @Size(max = 1000) String content,
        @Size(max = 30) String nickname) {
}
