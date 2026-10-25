package com.wzblog.modules.interaction.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.interaction.domain.ArticleReaction;
import com.wzblog.modules.interaction.domain.ReactionType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文章点赞/收藏明细。设置幂等由 uk_target 唯一键兜底。
 */
@Mapper
public interface ArticleReactionMapper extends BaseMapper<ArticleReaction> {

    @Insert("""
            INSERT IGNORE INTO article_reactions (article_id, user_id, type)
            VALUES (#{articleId}, #{userId}, #{type})
            """)
    int insertIgnore(@Param("articleId") Long articleId, @Param("userId") Long userId,
                     @Param("type") ReactionType type);

    @Delete("""
            DELETE FROM article_reactions
            WHERE article_id = #{articleId} AND user_id = #{userId} AND type = #{type}
            """)
    int deleteExact(@Param("articleId") Long articleId, @Param("userId") Long userId,
                    @Param("type") ReactionType type);

    @Select("""
            SELECT id, article_id, user_id, type, created_at
            FROM article_reactions
            WHERE user_id = #{userId} AND type = #{type}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ArticleReaction> selectMyPage(@Param("userId") Long userId, @Param("type") ReactionType type,
                                       @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*) FROM article_reactions
            WHERE article_id = #{articleId} AND type = #{type}
            """)
    long countByArticle(@Param("articleId") Long articleId, @Param("type") ReactionType type);

    @Select("""
            SELECT COUNT(*) FROM article_reactions
            WHERE user_id = #{userId} AND article_id = #{articleId} AND type = #{type}
            """)
    int exists(@Param("articleId") Long articleId, @Param("userId") Long userId,
               @Param("type") ReactionType type);

    /** 对账：按文章与类型重算明细数。 */
    @Select("""
            SELECT article_id, type, COUNT(*) AS cnt FROM article_reactions
            GROUP BY article_id, type
            """)
    List<ReactionCountRow> countGroupedByArticle();

    record ReactionCountRow(Long articleId, String type, Long cnt) {
    }
}
