package com.wzblog.modules.content.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.content.domain.ArticleRevision;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArticleRevisionMapper extends BaseMapper<ArticleRevision> {

    @Select("SELECT COALESCE(MAX(revision_no), 0) FROM article_revisions WHERE article_id = #{articleId}")
    int maxRevisionNo(@Param("articleId") Long articleId);

    @Select("""
            SELECT * FROM article_revisions
            WHERE article_id = #{articleId}
            ORDER BY revision_no DESC
            """)
    List<ArticleRevision> selectByArticleId(@Param("articleId") Long articleId);

    @Select("""
            SELECT * FROM article_revisions
            WHERE article_id = #{articleId} AND revision_no = #{revisionNo}
            """)
    ArticleRevision selectOneRevision(@Param("articleId") Long articleId, @Param("revisionNo") Integer revisionNo);

    /** 仅保留 revision_no 大于阈值的最近版本。 */
    @Delete("""
            DELETE FROM article_revisions
            WHERE article_id = #{articleId} AND revision_no <= #{threshold}
            """)
    int deleteOlderThan(@Param("articleId") Long articleId, @Param("threshold") int threshold);
}