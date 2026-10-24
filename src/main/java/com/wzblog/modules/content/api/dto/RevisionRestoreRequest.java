package com.wzblog.modules.content.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 修订回滚请求（M2 §4.1）。
 */
public record RevisionRestoreRequest(
        @Size(max = 255, message = "变更说明不能超过 255 字符")
        String changeNote
) {
}
