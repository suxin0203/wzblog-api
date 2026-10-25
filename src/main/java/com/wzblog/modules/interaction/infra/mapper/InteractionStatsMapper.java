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
}
