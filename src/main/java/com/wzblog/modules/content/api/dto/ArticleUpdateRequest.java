package com.wzblog.modules.content.api.dto;

import com.wzblog.modules.content.domain.ArticleVisibility;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 全量更新文章请求（M2 §4.1），乐观锁版本必填。
 */
public record ArticleUpdateRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 150, message = "标题不能超过 150 字符")
        String title,

        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug 只能包含小写字母、数字和中划线")
        @Size(min = 3, max = 180, message = "slug 长度需为 3~180")
        String slug,

        @Size(max = 300, message = "摘要不能超过 300 字符")
        String summary,

        @Size(max = 255, message = "封面 key 不能超过 255 字符")
        String coverKey,

        @NotBlank(message = "正文不能为空")
        @MaxUtf8Bytes(value = 204800, message = "正文不能超过 200 KB")
        String contentMd,

        String categoryId,

        @Size(max = 10, message = "标签最多 10 个")
        List<String> tagIds,

        ArticleVisibility visibility,

        Boolean allowComment,

        @NotNull(message = "version 不能为空")
        @Min(value = 0, message = "version 不能为负")
        Integer version,

        @Size(max = 255, message = "变更说明不能超过 255 字符")
        String changeNote
) {
}
