package com.wzblog.modules.content.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.profile.UserBrief;
import com.wzblog.common.util.Times;
import com.wzblog.modules.content.api.vo.ArticleDetailVO;
import com.wzblog.modules.content.api.vo.ArticleEditVO;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.api.vo.AuthorVO;
import com.wzblog.modules.content.api.vo.CategoryBriefVO;
import com.wzblog.modules.content.api.vo.TagBriefVO;
import com.wzblog.modules.content.api.vo.TocItemVO;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.Category;
import com.wzblog.modules.content.infra.mapper.ArticleTagMapper;
import com.wzblog.modules.content.infra.mapper.ArticleTagRow;
import com.wzblog.modules.content.infra.mapper.CategoryMapper;
import com.wzblog.modules.identity.app.UserQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章 VO 装配：作者经 M1 批量获取，分类/标签批量查询，禁止 JOIN users（M2 §4.2）。
 */
@Component
public class ArticleAssembler {

    private static final Logger log = LoggerFactory.getLogger(ArticleAssembler.class);

    private final UserQueryService userQueryService;
    private final CategoryMapper categoryMapper;
    private final ArticleTagMapper articleTagMapper;
    private final ObjectMapper objectMapper;

    public ArticleAssembler(UserQueryService userQueryService,
                            CategoryMapper categoryMapper,
                            ArticleTagMapper articleTagMapper,
                            ObjectMapper objectMapper) {
        this.userQueryService = userQueryService;
        this.categoryMapper = categoryMapper;
        this.articleTagMapper = articleTagMapper;
        this.objectMapper = objectMapper;
    }

    public List<ArticleListItemVO> toListItems(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        Context context = loadContext(articles);
        List<ArticleListItemVO> result = new ArrayList<>(articles.size());
        for (Article article : articles) {
            result.add(listItem(article, context));
        }
        return result;
    }

    public ArticleDetailVO toDetail(Article article) {
        Context context = loadContext(List.of(article));
        CategoryBriefVO category = category(article, context);
        List<TagBriefVO> tags = tags(article, context);
        AuthorVO author = author(article, context);
        return new ArticleDetailVO(
                article.getId(), article.getTitle(), article.getSlug(), article.getSummary(), article.getCoverKey(),
                category, tags, author,
                nz(article.getViewCount()), nz(article.getLikeCount()),
                nz(article.getFavoriteCount()), nz(article.getCommentCount()),
                Boolean.TRUE.equals(article.getIsPinned()), nz(article.getReadingSeconds()),
                Times.toInstant(article.getPublishedAt()),
                article.getContentHtml(), parseToc(article.getTocJson()),
                Boolean.TRUE.equals(article.getAllowComment()), article.getVisibility(),
                Times.toInstant(article.getCreatedAt()), Times.toInstant(article.getUpdatedAt()));
    }

    public ArticleEditVO toEdit(Article article) {
        Context context = loadContext(List.of(article));
        List<Long> tagIds = articleTagMapper.selectTagIds(article.getId());
        return new ArticleEditVO(
                article.getId(), article.getTitle(), article.getSlug(), article.getSummary(), article.getCoverKey(),
                category(article, context), tags(article, context), author(article, context),
                nz(article.getViewCount()), nz(article.getLikeCount()),
                nz(article.getFavoriteCount()), nz(article.getCommentCount()),
                Boolean.TRUE.equals(article.getIsPinned()), nz(article.getReadingSeconds()),
                Times.toInstant(article.getPublishedAt()),
                article.getContentMd(), article.getCategoryId(), tagIds,
                article.getVisibility(), Boolean.TRUE.equals(article.getAllowComment()),
                article.getStatus() == null ? null : article.getStatus().name(),
                nz(article.getVersion()),
                Times.toInstant(article.getCreatedAt()), Times.toInstant(article.getUpdatedAt()),
                Times.toInstant(article.getDeletedAt()));
    }

    public List<TocItemVO> parseToc(String tocJson) {
        if (tocJson == null || tocJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tocJson, new TypeReference<List<TocItemVO>>() {
            });
        } catch (Exception ex) {
            log.warn("TOC JSON 解析失败，按空目录返回", ex);
            return List.of();
        }
    }

    private Context loadContext(List<Article> articles) {
        Set<Long> authorIds = new LinkedHashSet<>();
        Set<Long> categoryIds = new LinkedHashSet<>();
        List<Long> articleIds = new ArrayList<>(articles.size());
        for (Article article : articles) {
            if (article.getAuthorId() != null) {
                authorIds.add(article.getAuthorId());
            }
            if (article.getCategoryId() != null) {
                categoryIds.add(article.getCategoryId());
            }
            articleIds.add(article.getId());
        }
        Map<Long, UserBrief> authors = userQueryService.getBriefs(authorIds);
        Map<Long, Category> categories = new LinkedHashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category category : categoryMapper.selectByIds(categoryIds)) {
                categories.put(category.getId(), category);
            }
        }
        Map<Long, List<ArticleTagRow>> tags = new LinkedHashMap<>();
        if (!articleIds.isEmpty()) {
            for (ArticleTagRow row : articleTagMapper.selectRowsByArticleIds(articleIds)) {
                tags.computeIfAbsent(row.getArticleId(), key -> new ArrayList<>()).add(row);
            }
        }
        return new Context(authors, categories, tags);
    }

    private ArticleListItemVO listItem(Article article, Context context) {
        return new ArticleListItemVO(
                article.getId(), article.getTitle(), article.getSlug(), article.getSummary(), article.getCoverKey(),
                category(article, context), tags(article, context), author(article, context),
                nz(article.getViewCount()), nz(article.getLikeCount()),
                nz(article.getFavoriteCount()), nz(article.getCommentCount()),
                Boolean.TRUE.equals(article.getIsPinned()), nz(article.getReadingSeconds()),
                Times.toInstant(article.getPublishedAt()));
    }

    private CategoryBriefVO category(Article article, Context context) {
        if (article.getCategoryId() == null) {
            return null;
        }
        Category category = context.categories().get(article.getCategoryId());
        return category == null ? null
                : new CategoryBriefVO(category.getId(), category.getName(), category.getSlug());
    }

    private List<TagBriefVO> tags(Article article, Context context) {
        List<ArticleTagRow> rows = context.tags().get(article.getId());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new TagBriefVO(row.getTagId(), row.getTagName(), row.getTagSlug()))
                .toList();
    }

    private AuthorVO author(Article article, Context context) {
        UserBrief brief = context.authors().get(article.getAuthorId());
        return brief == null ? null : new AuthorVO(brief.id(), brief.nickname(), brief.avatarKey());
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private record Context(Map<Long, UserBrief> authors,
                           Map<Long, Category> categories,
                           Map<Long, List<ArticleTagRow>> tags) {
    }
}
