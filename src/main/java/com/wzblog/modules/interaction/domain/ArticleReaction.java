package com.wzblog.modules.interaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * article_reactions 表实体：点赞/收藏共用，uk_target 幂等。
 */
@Getter
@Setter
@TableName("article_reactions")
public class ArticleReaction {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long userId;
    private ReactionType type;
    private LocalDateTime createdAt;
}
