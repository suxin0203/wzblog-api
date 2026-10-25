package com.wzblog.modules.content.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 文章读写。列表 SQL 一律只选白名单列，禁止 SELECT content_md/content_html/content_text。
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    String LIST_COLUMNS = """
            a.id, a.author_id, a.category_id, a.title, a.slug, a.summary, a.cover_key,
            a.word_count, a.reading_seconds, a.status, a.visibility, a.is_pinned, a.allow_comment,
            a.view_count, a.like_count, a.favorite_count, a.comment_count,
            a.view_internal_count, a.view_external_count, a.published_at, a.version,
            a.created_at, a.updated_at, a.deleted_at
            """;

    String PUBLIC_FILTER = " a.status = 'PUBLISHED' AND a.visibility = 'PUBLIC' "
            + "AND a.deleted_at IS NULL AND a.published_at IS NOT NULL";

    @Select("SELECT * FROM articles WHERE slug = #{slug}")
    Article selectBySlug(@Param("slug") String slug);

    @Select("SELECT * FROM articles WHERE id = #{id}")
    Article selectAnyById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND a.is_pinned = 0
            <if test="categoryId != null"> AND a.category_id = #{categoryId}</if>
            <if test="tagId != null">
              AND EXISTS (SELECT 1 FROM article_tags at WHERE at.article_id = a.id AND at.tag_id = #{tagId})
            </if>
            <if test="keyword != null and keyword != ''">
              AND (a.title LIKE CONCAT(#{keyword}, '%') OR a.summary LIKE CONCAT(#{keyword}, '%'))
            </if>
            <if test="from != null"> AND a.published_at &gt;= #{from}</if>
            <if test="to != null"> AND a.published_at &lt; #{to}</if>
            <if test="cursorTime != null">
              AND (a.published_at, a.id) &lt; (#{cursorTime}, #{cursorId})
            </if>
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Article> selectFeed(@Param("categoryId") Long categoryId,
                             @Param("tagId") Long tagId,
                             @Param("keyword") String keyword,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("cursorTime") LocalDateTime cursorTime,
                             @Param("cursorId") Long cursorId,
                             @Param("limit") int limit);

    @Select("""
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND a.is_pinned = 1
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT #{limit}
            """)
    List<Article> selectPinned(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
            ORDER BY
            <choose>
              <when test="type == 'LIKE'">a.like_count</when>
              <when test="type == 'FAVORITE'">a.favorite_count</when>
              <when test="type == 'COMMENT'">a.comment_count</when>
              <otherwise>a.view_count</otherwise>
            </choose> DESC, a.published_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Article> selectTop(@Param("type") String type, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT a.id, a.title, a.published_at,
                   MATCH(a.title, a.content_text) AGAINST(#{keyword} IN BOOLEAN MODE) AS score
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND MATCH(a.title, a.content_text) AGAINST(#{keyword} IN BOOLEAN MODE)
            <if test="categoryId != null"> AND a.category_id = #{categoryId}</if>
            <if test="tagId != null">
              AND EXISTS (SELECT 1 FROM article_tags at WHERE at.article_id = a.id AND at.tag_id = #{tagId})
            </if>
            ORDER BY score DESC, a.published_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<SearchHit> selectSearchHits(@Param("keyword") String keyword,
                                     @Param("categoryId") Long categoryId,
                                     @Param("tagId") Long tagId,
                                     @Param("limit") int limit);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND a.title LIKE CONCAT('%', #{keyword}, '%')
            <if test="categoryId != null"> AND a.category_id = #{categoryId}</if>
            <if test="tagId != null">
              AND EXISTS (SELECT 1 FROM article_tags at WHERE at.article_id = a.id AND at.tag_id = #{tagId})
            </if>
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Article> selectSearchLike(@Param("keyword") String keyword,
                                   @Param("categoryId") Long categoryId,
                                   @Param("tagId") Long tagId,
                                   @Param("limit") int limit);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND a.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Article> selectPublicByIds(@Param("ids") Collection<Long> ids);

    /**
     * 跨模块只读摘要：不取 content_*，包含软删行由调用方按 deletedAt 判定。
     */
    @Select("""
            <script>
            SELECT a.id, a.author_id, a.title, a.slug, a.status, a.visibility,
                   a.allow_comment, a.deleted_at, a.published_at
            FROM articles a
            WHERE a.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Article> selectBriefsByIds(@Param("ids") Collection<Long> ids);

    @Select("""
            <script>
            SELECT YEAR(DATE_ADD(a.published_at, INTERVAL 8 HOUR)) AS y, COUNT(*) AS cnt
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
            GROUP BY y
            ORDER BY y DESC
            </script>
            """)
    List<Map<String, Object>> selectArchiveYears();

    @Select("""
            <script>
            SELECT MONTH(DATE_ADD(a.published_at, INTERVAL 8 HOUR)) AS m, COUNT(*) AS cnt
            FROM articles a
            WHERE """ + PUBLIC_FILTER + """
              AND YEAR(DATE_ADD(a.published_at, INTERVAL 8 HOUR)) = #{year}
            GROUP BY m
            ORDER BY m DESC
            </script>
            """)
    List<Map<String, Object>> selectArchiveMonths(@Param("year") int year);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE a.status = 'PUBLISHED' AND a.visibility = 'PUBLIC' AND a.deleted_at IS NULL
              AND a.published_at IS NOT NULL
              AND (a.published_at &lt; #{cursorTime} OR (a.published_at = #{cursorTime} AND a.id &lt; #{cursorId}))
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Article> selectPublicAfterCursor(@Param("cursorTime") LocalDateTime cursorTime,
                                          @Param("cursorId") Long cursorId,
                                          @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM articles
            WHERE author_id = #{authorId} AND deleted_at IS NULL
            <if test="status != null"> AND status = #{status}</if>
            </script>
            """)
    long countMe(@Param("authorId") Long authorId, @Param("status") String status);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE a.author_id = #{authorId} AND a.deleted_at IS NULL
            <if test="status != null"> AND a.status = #{status}</if>
            ORDER BY a.updated_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Article> selectMe(@Param("authorId") Long authorId,
                           @Param("status") String status,
                           @Param("offset") long offset,
                           @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM articles a
            WHERE 1 = 1
            <if test="deleted != null">
              <choose>
                <when test="deleted"> AND a.deleted_at IS NOT NULL</when>
                <otherwise> AND a.deleted_at IS NULL</otherwise>
              </choose>
            </if>
            <if test="status != null"> AND a.status = #{status}</if>
            <if test="visibility != null"> AND a.visibility = #{visibility}</if>
            <if test="categoryId != null"> AND a.category_id = #{categoryId}</if>
            <if test="authorId != null"> AND a.author_id = #{authorId}</if>
            <if test="keyword != null and keyword != ''">
              AND (a.title LIKE CONCAT('%', #{keyword}, '%') OR a.summary LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countAdmin(@Param("status") String status,
                    @Param("visibility") String visibility,
                    @Param("deleted") Boolean deleted,
                    @Param("keyword") String keyword,
                    @Param("categoryId") Long categoryId,
                    @Param("authorId") Long authorId);

    @Select("""
            <script>
            SELECT
            """ + LIST_COLUMNS + """
            FROM articles a
            WHERE 1 = 1
            <if test="deleted != null">
              <choose>
                <when test="deleted"> AND a.deleted_at IS NOT NULL</when>
                <otherwise> AND a.deleted_at IS NULL</otherwise>
              </choose>
            </if>
            <if test="status != null"> AND a.status = #{status}</if>
            <if test="visibility != null"> AND a.visibility = #{visibility}</if>
            <if test="categoryId != null"> AND a.category_id = #{categoryId}</if>
            <if test="authorId != null"> AND a.author_id = #{authorId}</if>
            <if test="keyword != null and keyword != ''">
              AND (a.title LIKE CONCAT('%', #{keyword}, '%') OR a.summary LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY
            <choose>
              <when test="sort == 'createdAt'">a.created_at DESC</when>
              <when test="sort == 'publishedAt'">a.published_at DESC</when>
              <otherwise>a.updated_at DESC</otherwise>
            </choose>, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Article> selectAdmin(@Param("status") String status,
                              @Param("visibility") String visibility,
                              @Param("deleted") Boolean deleted,
                              @Param("keyword") String keyword,
                              @Param("categoryId") Long categoryId,
                              @Param("authorId") Long authorId,
                              @Param("sort") String sort,
                              @Param("offset") long offset,
                              @Param("limit") int limit);

    @Update("""
            UPDATE articles SET
              title = #{title}, summary = #{summary}, cover_key = #{coverKey}, category_id = #{categoryId},
              slug = #{slug}, content_md = #{contentMd}, content_html = #{contentHtml}, content_text = #{contentText},
              toc_json = #{tocJson}, word_count = #{wordCount}, reading_seconds = #{readingSeconds},
              visibility = #{visibility}, allow_comment = #{allowComment},
              version = version + 1, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND version = #{version} AND deleted_at IS NULL
            """)
    int updateContent(@Param("id") Long id,
                      @Param("version") Integer version,
                      @Param("title") String title,
                      @Param("summary") String summary,
                      @Param("coverKey") String coverKey,
                      @Param("categoryId") Long categoryId,
                      @Param("slug") String slug,
                      @Param("contentMd") String contentMd,
                      @Param("contentHtml") String contentHtml,
                      @Param("contentText") String contentText,
                      @Param("tocJson") String tocJson,
                      @Param("wordCount") Integer wordCount,
                      @Param("readingSeconds") Integer readingSeconds,
                      @Param("visibility") ArticleVisibility visibility,
                      @Param("allowComment") Boolean allowComment);

    @Update("""
            UPDATE articles SET status = 'PUBLISHED',
              published_at = COALESCE(published_at, UTC_TIMESTAMP(3)),
              version = version + 1, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL AND status IN ('DRAFT', 'ARCHIVED')
            """)
    int publish(@Param("id") Long id);

    @Update("""
            UPDATE articles SET status = 'ARCHIVED', is_pinned = 0,
              version = version + 1, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL AND status = 'PUBLISHED'
            """)
    int unpublish(@Param("id") Long id);

    @Update("""
            UPDATE articles SET is_pinned = 1, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL AND status = 'PUBLISHED'
            """)
    int pin(@Param("id") Long id);

    @Update("""
            UPDATE articles SET is_pinned = 0, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int unpin(@Param("id") Long id);

    @Update("""
            UPDATE articles SET deleted_at = UTC_TIMESTAMP(3), is_pinned = 0, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") Long id);

    @Update("""
            UPDATE articles SET deleted_at = NULL, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NOT NULL
            """)
    int restore(@Param("id") Long id);

    @Update("""
            UPDATE articles SET view_count = view_count + 1, view_internal_count = view_internal_count + 1
            WHERE id = #{id} AND deleted_at IS NULL AND status = 'PUBLISHED'
            """)
    int incrementViewInternal(@Param("id") Long id);

    @Update("""
            UPDATE articles SET view_count = view_count + 1, view_external_count = view_external_count + 1
            WHERE id = #{id} AND deleted_at IS NULL AND status = 'PUBLISHED'
            """)
    int incrementViewExternal(@Param("id") Long id);

    @Select("SELECT category_id AS category_id, COUNT(*) AS article_count FROM articles " +
            "WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC' AND deleted_at IS NULL " +
            "AND category_id IS NOT NULL GROUP BY category_id")
    List<Map<String, Object>> selectPublicCategoryCounts();

    @Select("""
            SELECT COUNT(*) AS article_count,
                   SUM(CASE WHEN status = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,
                   SUM(CASE WHEN status = 'ARCHIVED' THEN 1 ELSE 0 END) AS archived_count,
                   SUM(CASE WHEN status = 'DRAFT' THEN 1 ELSE 0 END) AS draft_count,
                   COALESCE(SUM(view_count), 0) AS total_views,
                   COALESCE(SUM(like_count), 0) AS total_likes,
                   COALESCE(SUM(favorite_count), 0) AS total_favorites,
                   COALESCE(SUM(comment_count), 0) AS total_comments
            FROM articles WHERE deleted_at IS NULL
            """)
    Map<String, Object> selectOverview();

    /**
     * M3 计数端口：单条原子 UPDATE，列名来自内部 {@code CounterType} 白名单（不拼接外部字符串）。
     */
    @Update("""
            <script>
            UPDATE articles SET
            <choose>
              <when test="type == 'LIKE'">like_count = like_count + #{delta}</when>
              <when test="type == 'FAVORITE'">favorite_count = favorite_count + #{delta}</when>
              <otherwise>comment_count = comment_count + #{delta}</otherwise>
            </choose>
            WHERE id = #{id} AND deleted_at IS NULL
            </script>
            """)
    int incrementCounter(@Param("id") Long id, @Param("type") String type, @Param("delta") int delta);

    /** M7 看板：区间发布数。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) FROM articles
            WHERE status = 'PUBLISHED' AND deleted_at IS NULL
              AND published_at >= #{from} AND published_at < #{to}
            """)
    long countPublishedBetween(@org.apache.ibatis.annotations.Param("from") java.time.LocalDateTime from,
                               @org.apache.ibatis.annotations.Param("to") java.time.LocalDateTime to);

    /** 对账候选：任一计数非零的文章（与明细聚合取并集）。 */
    @Select("""
            SELECT id FROM articles
            WHERE like_count > 0 OR favorite_count > 0 OR comment_count > 0
            """)
    java.util.List<Long> selectIdsWithCounters();

    @Update("""
            <script>
            UPDATE articles SET
            <choose>
              <when test="type == 'LIKE'">like_count = GREATEST(like_count - #{delta}, 0)</when>
              <when test="type == 'FAVORITE'">favorite_count = GREATEST(favorite_count - #{delta}, 0)</when>
              <otherwise>comment_count = GREATEST(comment_count - #{delta}, 0)</otherwise>
            </choose>
            WHERE id = #{id} AND deleted_at IS NULL AND
            <choose>
              <when test="type == 'LIKE'">like_count</when>
              <when test="type == 'FAVORITE'">favorite_count</when>
              <otherwise>comment_count</otherwise>
            </choose> &gt; 0
            </script>
            """)
    int decrementCounter(@Param("id") Long id, @Param("type") String type, @Param("delta") int delta);

    @Select("""
            <script>
            SELECT
            <choose>
              <when test="type == 'LIKE'">like_count</when>
              <when test="type == 'FAVORITE'">favorite_count</when>
              <otherwise>comment_count</otherwise>
            </choose>
            FROM articles WHERE id = #{id}
            </script>
            """)
    Integer selectCounter(@Param("id") Long id, @Param("type") String type);

    @Delete("DELETE FROM articles WHERE id = #{id}")
    int purge(@Param("id") Long id);
}