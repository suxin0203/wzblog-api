package com.wzblog.modules.interaction.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 互动统计（M7 看板）。
 */
@Mapper
public interface InteractionStatsMapper {

    @Select("""
            SELECT COUNT(*) FROM comments
            WHERE created_at >= #{from} AND created_at < #{to}
            """)
    long countCommentsCreatedBetween(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM article_reactions
            WHERE type = #{type} AND created_at >= #{from} AND created_at < #{to}
            """)
    long countReactionsBetween(@Param("type") String type,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    /** 对账活跃窗口：该文章近窗口内是否有互动明细写入（点赞/收藏）。 */
    @Select("""
            SELECT COUNT(*) FROM article_reactions
            WHERE article_id = #{articleId} AND created_at > #{since}
            """)
    long countRecentReactionsByArticle(@Param("articleId") Long articleId,
                                       @Param("since") LocalDateTime since);
}
