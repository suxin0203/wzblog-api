package com.wzblog.modules.admin.infra.mapper;

import com.wzblog.modules.admin.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志查询（只读）。
 */
@Mapper
public interface AuditQueryMapper {

    @Select("""
            <script>
            SELECT id, operator_id, action, target_type, target_id, before_json, after_json, ip, created_at
            FROM audit_logs
            <where>
                <if test="action != null and action != ''"> AND action = #{action}</if>
                <if test="targetType != null and targetType != ''"> AND target_type = #{targetType}</if>
                <if test="operatorId != null"> AND operator_id = #{operatorId}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLog> selectPage(@Param("action") String action, @Param("targetType") String targetType,
                              @Param("operatorId") Long operatorId,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                              @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM audit_logs
            <where>
                <if test="action != null and action != ''"> AND action = #{action}</if>
                <if test="targetType != null and targetType != ''"> AND target_type = #{targetType}</if>
                <if test="operatorId != null"> AND operator_id = #{operatorId}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countPage(@Param("action") String action, @Param("targetType") String targetType,
                   @Param("operatorId") Long operatorId, @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);
}
