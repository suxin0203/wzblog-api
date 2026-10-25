package com.wzblog.modules.interaction.infra.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评论点赞：联合主键 (comment_id, user_id) 天然幂等。
 */
@Mapper
public interface CommentLikeMapper {

    @Insert("""
            INSERT IGNORE INTO comment_likes (comment_id, user_id)
            VALUES (#{commentId}, #{userId})
            """)
    int insertIgnore(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @Delete("DELETE FROM comment_likes WHERE comment_id = #{commentId} AND user_id = #{userId}")
    int deleteExact(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) FROM comment_likes WHERE comment_id = #{commentId} AND user_id = #{userId}
            """)
    int exists(@Param("commentId") Long commentId, @Param("userId") Long userId);
}
