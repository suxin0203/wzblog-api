package com.wzblog.modules.interaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审核请求：目标状态不得为 PENDING（服务端校验）。
 */
public record ModerateRequest(
        @NotNull @NotBlank String status,
        @Size(max = 220) String remark) {
}
