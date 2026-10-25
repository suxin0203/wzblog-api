package com.wzblog.modules.interaction.domain.event;

import com.wzblog.modules.interaction.domain.ReactionType;

/**
 * 文章点赞切换事件（仅 LIKE；M4 按 like/unlike 幂等键消费）。
 */
public record ReactionToggledEvent(Long articleId, Long authorId, Long actorId,
                                   ReactionType type, boolean active) {

    /** 跨模块消费方判断（避免外部触碰本模块枚举）。 */
    public boolean isLike() {
        return type == ReactionType.LIKE;
    }
}
