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

    /**
     * 公开树可见评论口径：根评论已过审未删；子评论已过审未删，且根评论同文章、已过审。
     * 根评论软删时自身不计，但其已过审子评论仍可见（列表保留删除占位）。
     */
    String VISIBLE_FILTER = " c.status = 'APPROVED' AND c.deleted_at IS NULL "
            + "AND (c.root_id IS NULL OR EXISTS ("
            + "SELECT 1 FROM comments r WHERE r.id = c.root_id "
            + "AND r.article_id = c.article_id AND r.status = 'APPROVED')) ";

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

    /** 根评论的子评论（APPROVED + 本人 PENDING，时间升序）；限定文章，防止跨文章 root_id 脏数据串树。 */
    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM comments
            WHERE root_id IN
            <foreach collection="rootIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND article_id = #{articleId}
              AND (status = 'APPROVED'
                   <if test="viewerId != null"> OR (status = 'PENDING' AND user_id = #{viewerId})</if>)
              AND deleted_at IS NULL
            ORDER BY created_at ASC, id ASC
            LIMIT 2147483647
            </script>
            """)
    List<Comment> selectChildrenOf(@Param("rootIds") List<Long> rootIds,
                                   @Param("articleId") Long articleId,
                                   @Param("viewerId") Long viewerId);

    /** rootId 模式：直接分页返回该根的子评论（同样限定文章；本人 PENDING 可见）。 */
    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM comments
            WHERE root_id = #{rootId} AND article_id = #{articleId}
              AND (status = 'APPROVED'
                   <if test="viewerId != null"> OR (status = 'PENDING' AND user_id = #{viewerId})</if>)
              AND deleted_at IS NULL
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Comment> selectChildPage(@Param("rootId") Long rootId,
                                  @Param("articleId") Long articleId,
                                  @Param("viewerId") Long viewerId,
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

    /** 公开树可见评论数（根 + 可见子回复）。 */
    @Select("""
            SELECT COUNT(*) FROM comments c
            WHERE c.article_id = #{articleId} AND
            """ + VISIBLE_FILTER)
    int countVisibleByArticle(@Param("articleId") Long articleId);

    /** 对账：按公开树可见口径重算文章评论数。 */
    @Select("""
            SELECT c.article_id, COUNT(*) AS cnt FROM comments c
            WHERE
            """ + VISIBLE_FILTER + """
            GROUP BY c.article_id
            """)
    List<CountRow> countVisibleByArticleGrouped();

    /**
     * 检测不可见/跨文章的已过审回复：只记录，不自动改状态。
     */
    @Select("""
            SELECT c.id, c.article_id, c.root_id, r.article_id AS root_article_id,
                   c.status AS child_status, r.status AS root_status
            FROM comments c LEFT JOIN comments r ON r.id = c.root_id
            WHERE c.deleted_at IS NULL AND c.root_id IS NOT NULL
              AND (r.id IS NULL OR r.article_id <> c.article_id
                   OR (c.status = 'APPROVED' AND r.status <> 'APPROVED'))
            """)
    List<VisibilityAnomalyRow> findVisibilityAnomalies();

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

    record VisibilityAnomalyRow(Long id, Long articleId, Long rootId, Long rootArticleId,
                                String childStatus, String rootStatus) {
    }

    record CommentLikeRow(Long id, Integer likeCount, Long cnt) {
    }
}
