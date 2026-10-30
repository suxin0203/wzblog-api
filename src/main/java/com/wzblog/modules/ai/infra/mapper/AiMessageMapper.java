package com.wzblog.modules.ai.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.ai.domain.AiMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 消息读写。
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    String COLUMNS = """
            id, session_id, role, content, status, tool_name, tool_call_id, tool_args_json,
            tool_result_json, prompt_tokens, completion_tokens, latency_ms, model, created_at,
            updated_at
            """;

    @Insert("""
            INSERT INTO ai_messages (session_id, role, content, status, tool_name, tool_call_id,
                                     tool_args_json, tool_result_json, created_at)
            VALUES (#{sessionId}, #{role}, #{content}, #{status}, #{toolName}, #{toolCallId},
                    #{toolArgsJson}, #{toolResultJson}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMessage(AiMessage message);

    @Select("SELECT " + COLUMNS + " FROM ai_messages WHERE id = #{id}")
    AiMessage selectById(@Param("id") Long id);

    /** 会话消息（含 TOOL，供上下文与后台详情），afterId 用于游标翻页。 */
    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM ai_messages
            WHERE session_id = #{sessionId}
            <if test="afterId != null"> AND id &gt; #{afterId}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<AiMessage> selectBySession(@Param("sessionId") String sessionId,
                                    @Param("afterId") Long afterId, @Param("limit") int limit);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM ai_messages
            WHERE session_id = #{sessionId} AND role IN ('USER', 'ASSISTANT') AND status = 'COMPLETE'
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AiMessage> selectRecentForContext(@Param("sessionId") String sessionId,
                                           @Param("limit") int limit);

    @Update("""
            UPDATE ai_messages
            SET content = #{content}, status = #{status}, prompt_tokens = #{promptTokens},
                completion_tokens = #{completionTokens}, latency_ms = #{latencyMs},
                model = #{model}, updated_at = #{now}
            WHERE id = #{id} AND status = 'STREAMING'
            """)
    int finishStreaming(@Param("id") Long id, @Param("content") String content,
                        @Param("status") String status, @Param("promptTokens") Integer promptTokens,
                        @Param("completionTokens") Integer completionTokens,
                        @Param("latencyMs") Integer latencyMs, @Param("model") String model,
                        @Param("now") LocalDateTime now);

    /** 崩溃兜底：超时未完成的 STREAMING 置 INTERRUPTED。 */
    @Update("""
            UPDATE ai_messages SET status = 'INTERRUPTED', updated_at = #{now}
            WHERE status = 'STREAMING' AND created_at < #{threshold}
            """)
    int markStaleInterrupted(@Param("threshold") LocalDateTime threshold,
                             @Param("now") LocalDateTime now);

    // ------------------------------------------------------------ 统计（本模块自有表）

    /** 统计口径：软删会话一律不计入（D82）。 */
    String LIVE_SESSION_FILTER =
            " AND EXISTS (SELECT 1 FROM ai_sessions s "
                    + "WHERE s.id = ai_messages.session_id AND s.deleted_at IS NULL) ";

    @Select("SELECT COUNT(*) FROM ai_messages WHERE created_at >= #{from} AND created_at < #{to}"
            + LIVE_SESSION_FILTER)
    long countInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(prompt_tokens), 0) FROM ai_messages
            WHERE created_at >= #{from} AND created_at < #{to}
            """ + LIVE_SESSION_FILTER)
    long sumPromptTokens(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(completion_tokens), 0) FROM ai_messages
            WHERE created_at >= #{from} AND created_at < #{to}
            """ + LIVE_SESSION_FILTER)
    long sumCompletionTokens(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(AVG(latency_ms), 0) FROM ai_messages
            WHERE role = 'ASSISTANT' AND latency_ms IS NOT NULL
              AND created_at >= #{from} AND created_at < #{to}
            """ + LIVE_SESSION_FILTER)
    long avgLatency(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(MAX(latency_ms), 0) FROM ai_messages
            WHERE role = 'ASSISTANT' AND latency_ms IS NOT NULL
              AND created_at >= #{from} AND created_at < #{to}
            """ + LIVE_SESSION_FILTER)
    long maxLatency(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM ai_messages
            WHERE role = 'TOOL' AND created_at >= #{from} AND created_at < #{to}
            """ + LIVE_SESSION_FILTER)
    long countToolCalls(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM ai_messages
            WHERE role = 'USER' AND created_at >= #{from} AND created_at < #{to}
            """)
    long countUserMessages(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
