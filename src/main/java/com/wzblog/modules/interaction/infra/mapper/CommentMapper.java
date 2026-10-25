package com.wzblog.modules.interaction.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.interaction.domain.Comment;
import com.wzblog.modules.interaction.domain.CommentStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论读写。列表查询只选白名单列，不返回 ip/user_agent（公开侧）。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    String COLUMNS = """
            id, article_id, user_id, guest_name, root_id, parent_id, reply_to_user_id,
            content, status, like_count, created_at, updated_at, deleted_at
            """;

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM comments
            WHERE article_id = #{articleId} AND root_id IS NULL
              AND (status = 'APPROVED'
                   <if test="viewerId != null"> OR (status = 'PENDING' AND user_id = #{viewerId})</if>)
              AND (deleted_at IS NULL OR EXISTS (
                   SELECT 1 FROM comments c2 WHERE c2.root_id = comments.id
                     AND c2.status = 'APPROVED' AND c2.deleted_at IS NULL))
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Comment> selectRootPage(@Param("articleId") Long articleId,
                                 @Param("viewerId") Long viewerId,
                                 @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM comments
            WHERE article_id = #{articleId} AND root_id IS NULL
              AND (status = 'APPROVED'
                   <if test="viewerId != null"> OR (status = 'PENDING' AND user_id = #{viewerId})</if>)
              AND (deleted_at IS NULL OR EXISTS (
                   SELECT 1 FROM comments c2 WHERE c2.root_id = comments.id
                     AND c2.status = 'APPROVED' AND c2.deleted_at IS NULL))
            </script>
            """)
    long countRootPage(@Param("articleId") Long articleId, @Param("viewerId") Long viewerId);

    /** 根评论的子评论（APPROVED，时间升序），每根最多 limit+1 条以判定 hasMore。 */
    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM comments
            WHERE root_id IN
            <foreach collection="rootIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND status = 'APPROVED'
            ORDER BY created_at ASC, id ASC
            LIMIT 2147483647
            </script>
            """)
    List<Comment> selectChildrenOf(@Param("rootIds") List<Long> rootIds);

    /** rootId 模式：直接分页返回该根的子评论。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM comments
            WHERE root_id = #{rootId} AND status = 'APPROVED' AND deleted_at IS NULL
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Comment> selectChildPage(@Param("rootId") Long rootId,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT " + COLUMNS + " FROM comments WHERE id = #{id}")
    Comment selectByIdIncludeDeleted(@Param("id") Long id);

    /** 审核状态条件迁移；影响行数 0 表示已被并发修改或已同状态。 */
    @Update("""
            UPDATE comments
            SET status = #{to}, updated_at = #{now}
            WHERE id = #{id} AND status = #{from} AND deleted_at IS NULL
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") CommentStatus from,
                         @Param("to") CommentStatus to, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE comments
            SET deleted_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE comments
            SET like_count = like_count + #{delta}, updated_at = #{now}
            WHERE id = #{id} AND like_count + #{delta} >= 0
            """)
    int adjustLikeCount(@Param("id") Long id, @Param("delta") int delta,
                        @Param("now") LocalDateTime now);

    /** 后台审核列表。 */
    @Select("""
            <script>
            SELECT id, article_id, user_id, guest_name, root_id, parent_id, content, status,
                   like_count, ip, created_at, updated_at, deleted_at
            FROM comments
            <where>
                deleted_at IS NULL
                <if test="status != null"> AND status = #{status}</if>
                <if test="articleId != null"> AND article_id = #{articleId}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Comment> selectAdminPage(@Param("status") CommentStatus status,
                                  @Param("articleId") Long articleId,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM comments
            <where>
                deleted_at IS NULL
                <if test="status != null"> AND status = #{status}</if>
                <if test="articleId != null"> AND article_id = #{articleId}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("status") CommentStatus status, @Param("articleId") Long articleId);

    /** 对账：重算文章评论数（仅 APPROVED 未删）。 */
    @Select("""
            SELECT article_id, COUNT(*) AS cnt FROM comments
            WHERE status = 'APPROVED' AND deleted_at IS NULL
            GROUP BY article_id
            """)
    List<CountRow> countApprovedByArticle();

    /** 评论点赞明细对账。 */
    @Select("""
            SELECT c.id, c.like_count, COUNT(cl.user_id) AS cnt
            FROM comments c LEFT JOIN comment_likes cl ON cl.comment_id = c.id
            WHERE c.status = 'APPROVED' AND c.deleted_at IS NULL
            GROUP BY c.id, c.like_count
            """)
    List<CommentLikeRow> countCommentLikes();

    /** 互动明细近十分钟是否有写入（对账安全窗口）。 */
    @Select("""
            SELECT COUNT(*) FROM comments
            WHERE article_id = #{articleId} AND created_at > #{since}
            """)
    long countRecentByArticle(@Param("articleId") Long articleId, @Param("since") LocalDateTime since);

    record CountRow(Long articleId, Long cnt) {
    }

    record CommentLikeRow(Long id, Integer likeCount, Long cnt) {
    }
}
