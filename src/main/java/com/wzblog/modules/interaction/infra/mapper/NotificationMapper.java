package com.wzblog.modules.interaction.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.interaction.domain.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知读写：dedupe_key 唯一键去重，未读数走 idx_recipient_read。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    String COLUMNS = """
            id, recipient_id, actor_id, type, title, content, link_type, link_id, dedupe_key,
            is_read, read_at, created_at
            """;

    /** 通知插入（回填 id）；dedupe 冲突由服务层捕获 DuplicateKeyException 处理。 */
    @Insert("""
            INSERT INTO notifications
                (recipient_id, actor_id, type, title, content, link_type, link_id, dedupe_key, is_read, created_at)
            VALUES
                (#{recipientId}, #{actorId}, #{type}, #{title}, #{content}, #{linkType}, #{linkId},
                 #{dedupeKey}, 0, #{createdAt})
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlain(Notification notification);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM notifications
            WHERE recipient_id = #{recipientId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Notification> selectPage(@Param("recipientId") Long recipientId,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM notifications WHERE recipient_id = #{recipientId}")
    long countByRecipient(@Param("recipientId") Long recipientId);

    @Select("SELECT COUNT(*) FROM notifications WHERE recipient_id = #{recipientId} AND is_read = 0")
    long countUnread(@Param("recipientId") Long recipientId);

    @Update("""
            <script>
            UPDATE notifications
            SET is_read = 1, read_at = #{now}
            WHERE recipient_id = #{recipientId} AND is_read = 0
              AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markRead(@Param("recipientId") Long recipientId, @Param("ids") List<Long> ids,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notifications
            SET is_read = 1, read_at = #{now}
            WHERE recipient_id = #{recipientId} AND is_read = 0
            """)
    int markAllRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);

    /** SSE 断线续传：补发 Last-Event-ID 之后的通知。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM notifications
            WHERE recipient_id = #{recipientId} AND id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<Notification> selectAfterId(@Param("recipientId") Long recipientId,
                                     @Param("afterId") Long afterId, @Param("limit") int limit);
}
