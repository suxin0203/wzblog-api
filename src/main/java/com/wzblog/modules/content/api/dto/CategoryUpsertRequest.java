package com.wzblog.modules.content.api.dto;

import com.wzblog.modules.content.domain.TaxonomyStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 分类新建/更新请求（M2 §4.1）。
 */
public record CategoryUpsertRequest(
        @NotBlank(message = "分类名不能为空")
        @Size(max = 50, message = "分类名不能超过 50 字符")
        String name,

        @NotBlank(message = "slug 不能为空")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug 只能包含小写字母、数字和中划线")
        @Size(min = 3, max = 60, message = "slug 长度需为 3~60")
        String slug,

        @Size(max = 255, message = "描述不能超过 255 字符")
        String description,

        @Min(value = -100000, message = "排序值过小")
        @Max(value = 100000, message = "排序值过大")
        Integer sortOrder,

        TaxonomyStatus status
) {
}
