package com.wzblog.modules.ai.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.ai.domain.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话读写。
 */
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {

    String COLUMNS = """
            id, user_id, channel, title, message_count, last_message_at, created_at, updated_at,
            deleted_at
            """;

    @Select("SELECT " + COLUMNS + " FROM ai_sessions WHERE id = #{id}")
    AiSession selectIncludeDeleted(@Param("id") String id);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM ai_sessions
            <where>
                deleted_at IS NULL
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="channel != null and channel != ''"> AND channel = #{channel}</if>
                <if test="from != null"> AND last_message_at &gt;= #{from}</if>
                <if test="to != null"> AND last_message_at &lt; #{to}</if>
            </where>
            ORDER BY last_message_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiSession> selectAdminPage(@Param("userId") Long userId, @Param("channel") String channel,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_sessions
            <where>
                deleted_at IS NULL
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="channel != null and channel != ''"> AND channel = #{channel}</if>
                <if test="from != null"> AND last_message_at &gt;= #{from}</if>
                <if test="to != null"> AND last_message_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("userId") Long userId, @Param("channel") String channel,
                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Update("UPDATE ai_sessions SET message_count = message_count + #{delta}, "
            + "last_message_at = #{now}, updated_at = #{now} WHERE id = #{id}")
    int bumpMessageCount(@Param("id") String id, @Param("delta") int delta,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE ai_sessions SET deleted_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") String id, @Param("now") LocalDateTime now);
}
