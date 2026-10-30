package com.wzblog.modules.media.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 文件实时引用探查：逐一检查各业务引用点（字段等值与正文文本内嵌），每处仅取 1 条。
 * ref_count 仅作快筛提示，删除判定以本查询为权威（D55）。
 */
@Mapper
public interface FileReferenceQueryMapper {

    @Select("""
            SELECT * FROM (
              (SELECT 'articles' AS tableName, 'cover_key' AS columnName, id AS refId,
                      title AS label, 0 AS historical FROM articles
               WHERE cover_key = #{objectKey} LIMIT 1)
              UNION ALL
              (SELECT 'banners' AS tableName, 'image_key' AS columnName, id AS refId,
                      title AS label, 0 AS historical FROM banners
               WHERE image_key = #{objectKey} LIMIT 1)
              UNION ALL
              (SELECT 'users' AS tableName, 'avatar_key' AS columnName, id AS refId,
                      nickname AS label, 0 AS historical FROM users
               WHERE avatar_key = #{objectKey} LIMIT 1)
              UNION ALL
              (SELECT 'points_goods' AS tableName, 'image_key' AS columnName, id AS refId,
                      name AS label, 0 AS historical FROM points_goods
               WHERE image_key = #{objectKey} LIMIT 1)
              UNION ALL
              (SELECT 'friend_links' AS tableName, 'logo_key' AS columnName, id AS refId,
                      blog_name AS label, 0 AS historical FROM friend_links
               WHERE logo_key = #{objectKey} LIMIT 1)
              UNION ALL
              (SELECT 'articles' AS tableName, 'content_md' AS columnName, id AS refId,
                      title AS label, 0 AS historical FROM articles
               WHERE content_md LIKE CONCAT('%', #{objectKey}, '%') LIMIT 1)
              UNION ALL
              (SELECT 'articles' AS tableName, 'content_html' AS columnName, id AS refId,
                      title AS label, 0 AS historical FROM articles
               WHERE content_html LIKE CONCAT('%', #{objectKey}, '%') LIMIT 1)
              UNION ALL
              (SELECT 'articles' AS tableName, 'content_text' AS columnName, id AS refId,
                      title AS label, 0 AS historical FROM articles
               WHERE content_text LIKE CONCAT('%', #{objectKey}, '%') LIMIT 1)
              UNION ALL
              (SELECT 'article_revisions' AS tableName, 'content_md' AS columnName, id AS refId,
                      title AS label, 1 AS historical FROM article_revisions
               WHERE content_md LIKE CONCAT('%', #{objectKey}, '%') LIMIT 1)
            ) t
            """)
    List<Map<String, Object>> selectReferences(@Param("objectKey") String objectKey);
}
