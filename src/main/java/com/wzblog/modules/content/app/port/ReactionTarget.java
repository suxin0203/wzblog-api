package com.wzblog.modules.content.app.port;

/**
 * M3 交互目标校验结果；{@code interactive=false} 表示不可交互。
 */
public record ReactionTarget(
        Long id,
        Long authorId,
        boolean interactive,
        boolean allowComment
) {
}
