package com.wzblog.modules.interaction.domain.event;

import com.wzblog.modules.interaction.domain.ReactionType;

/**
 * 文章点赞切换事件（仅 LIKE；M4 按 like/unlike 幂等键消费）。
 */
public record ReactionToggledEvent(Long articleId, Long authorId, Long actorId,
                                   ReactionType type, boolean active) {
}
