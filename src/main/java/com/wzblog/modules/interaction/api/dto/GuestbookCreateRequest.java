package com.wzblog.modules.interaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表留言请求。
 */
public record GuestbookCreateRequest(
        @NotBlank @Size(max = 1100) String content,
        @Size(max = 40) String nickname) {
}
