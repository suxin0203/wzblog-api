package com.wzblog.modules.interaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量已读请求：1~100 个 id。
 */
public record NotificationReadRequest(@NotNull List<@NotBlank String> ids) {
}
