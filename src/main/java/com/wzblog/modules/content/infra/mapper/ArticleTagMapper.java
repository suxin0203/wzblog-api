package com.wzblog.modules.content.infra.mapper;

import com.wzblog.modules.content.infra.mapper.ArticleTagRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * article_tags 无独立主键，使用显式 SQL 维护；保存时全量替换。
 */
@Mapper
public interface ArticleTagMapper {

    @Insert("INSERT INTO article_tags (article_id, tag_id) VALUES (#{articleId}, #{tagId})")
    int insert(@Param("articleId") Long articleId, @Param("tagId") Long tagId);

    @Delete("DELETE FROM article_tags WHERE article_id = #{articleId}")
    int deleteByArticleId(@Param("articleId") Long articleId);

    @Select("SELECT tag_id FROM article_tags WHERE article_id = #{articleId} ORDER BY tag_id")
    List<Long> selectTagIds(@Param("articleId") Long articleId);

    @Select("""
            <script>
            SELECT at.article_id AS article_id, t.id AS tag_id, t.name AS tag_name, t.slug AS tag_slug
            FROM article_tags at
            JOIN tags t ON t.id = at.tag_id
            WHERE at.article_id IN
            <foreach collection="articleIds" item="articleId" open="(" separator="," close=")">#{articleId}</foreach>
            ORDER BY at.article_id, t.id
            </script>
            """)
    List<ArticleTagRow> selectRowsByArticleIds(@Param("articleIds") Collection<Long> articleIds);

    @Select("""
            SELECT at.tag_id AS tag_id, COUNT(*) AS article_count
            FROM article_tags at
            JOIN articles a ON a.id = at.article_id
            WHERE a.status = 'PUBLISHED' AND a.visibility = 'PUBLIC' AND a.deleted_at IS NULL
            GROUP BY at.tag_id
            """)
    List<java.util.Map<String, Object>> selectPublicTagCounts();
}