package com.wzblog.modules.interaction.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.interaction.domain.CommentStatus;
import com.wzblog.modules.interaction.domain.GuestbookMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 留言板读写。
 */
@Mapper
public interface GuestbookMapper extends BaseMapper<GuestbookMessage> {

    String COLUMNS = """
            id, user_id, nickname, content, status, created_at, updated_at, deleted_at
            """;

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM guestbook_messages
            WHERE status = 'APPROVED' AND deleted_at IS NULL
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<GuestbookMessage> selectPublicPage(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM guestbook_messages WHERE status = 'APPROVED' AND deleted_at IS NULL")
    long countPublic();

    @Select("SELECT " + COLUMNS + " FROM guestbook_messages WHERE id = #{id}")
    GuestbookMessage selectByIdIncludeDeleted(@Param("id") Long id);

    @Update("""
            UPDATE guestbook_messages
            SET status = #{to}, updated_at = #{now}
            WHERE id = #{id} AND status = #{from} AND deleted_at IS NULL
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") CommentStatus from,
                         @Param("to") CommentStatus to, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE guestbook_messages
            SET deleted_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT id, user_id, nickname, content, status, ip, created_at, updated_at, deleted_at
            FROM guestbook_messages
            <where>
                deleted_at IS NULL
                <if test="status != null"> AND status = #{status}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<GuestbookMessage> selectAdminPage(@Param("status") CommentStatus status,
                                           @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM guestbook_messages
            <where>
                deleted_at IS NULL
                <if test="status != null"> AND status = #{status}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("status") CommentStatus status);
}
