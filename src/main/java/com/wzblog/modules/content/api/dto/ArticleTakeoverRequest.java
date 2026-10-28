package com.wzblog.modules.content.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 接管请求：站主把文章作者改为自己（D2）。reason 选填，进审计与通知。
 */
public record ArticleTakeoverRequest(
        @Size(max = 255, message = "reason 不能超过 255 字符") String reason) {
}
