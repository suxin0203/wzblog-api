package com.wzblog.modules.ai.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.ai.domain.AiFeedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * AI 反馈读写。
 */
@Mapper
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {

    @Insert("""
            INSERT INTO ai_feedbacks (message_id, user_id, rating, comment, created_at)
            VALUES (#{messageId}, #{userId}, #{rating}, #{comment}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFeedback(AiFeedback feedback);

    /** 登录用户按 (message_id, user_id)；游客 user_id=NULL 按 message_id 汇总（应用层结合会话判重）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_feedbacks
            WHERE message_id = #{messageId}
            <if test="userId != null"> AND user_id = #{userId}</if>
            <if test="userId == null"> AND user_id IS NULL</if>
            </script>
            """)
    long countByMessageAndUser(@Param("messageId") Long messageId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM ai_feedbacks
            WHERE rating = 1 AND created_at >= #{from} AND created_at < #{to}
            """)
    long countPositive(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM ai_feedbacks
            WHERE rating = -1 AND created_at >= #{from} AND created_at < #{to}
            """)
    long countNegative(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
