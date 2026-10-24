package com.wzblog.modules.content.app;

import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.app.port.PublicArticleContent;
import com.wzblog.modules.content.app.port.ReactionTarget;
import com.wzblog.modules.content.api.vo.CategoryVO;
import com.wzblog.modules.content.api.vo.TagVO;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import com.wzblog.modules.content.infra.mapper.SearchHit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M2 对其他模块开放的只读端口（M2 §16.1），共 9 个方法。
 */
@Service
public class ArticleQueryService {

    private static final int MAX_BRIEFS = 50;
    private static final int MAX_SEARCH = 20;
    private static final int CONTENT_TEXT_LIMIT = 6000;

    private final ArticleMapper articleMapper;
    private final TaxonomyService taxonomyService;

    public ArticleQueryService(ArticleMapper articleMapper, TaxonomyService taxonomyService) {
        this.articleMapper = articleMapper;
        this.taxonomyService = taxonomyService;
    }

    @Transactional(readOnly = true)
    public ArticleBrief getBrief(Long id) {
        if (id == null) {
            return null;
        }
        return toBrief(articleMapper.selectAnyById(id));
    }

    @Transactional(readOnly = true)
    public List<ArticleBrief> getBriefs(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> ordered = ids.stream().filter(Objects::nonNull).distinct().limit(MAX_BRIEFS).toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        Map<Long, ArticleBrief> byId = new HashMap<>();
        for (Article article : articleMapper.selectBriefsByIds(ordered)) {
            ArticleBrief brief = toBrief(article);
            if (brief != null) {
                byId.put(brief.id(), brief);
            }
        }
        List<ArticleBrief> result = new ArrayList<>(byId.size());
        for (Long id : ordered) {
            ArticleBrief brief = byId.get(id);
            if (brief != null) {
                result.add(brief);
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PublicArticleContent getPublicBySlug(String slug) {
        Article article = articleMapper.selectBySlug(slug);
        if (article == null || article.isDeleted() || article.getStatus() != ArticleStatus.PUBLISHED
                || article.getVisibility() != ArticleVisibility.PUBLIC) {
            return null;
        }
        String text = article.getContentText() == null ? "" : article.getContentText();
        if (text.length() > CONTENT_TEXT_LIMIT) {
            text = text.substring(0, CONTENT_TEXT_LIMIT);
        }
        return new PublicArticleContent(article.getId(), article.getAuthorId(), article.getTitle(),
                article.getSlug(), text, article.getPublishedAt());
    }

    @Transactional(readOnly = true)
    public List<ArticleBrief> searchPublic(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int size = Math.min(Math.max(limit, 1), MAX_SEARCH);
        String keyword = query.trim();
        List<Article> articles;
        if (keyword.length() < 2) {
            articles = articleMapper.selectSearchLike(keyword, null, null, size);
        } else {
            List<SearchHit> hits = articleMapper.selectSearchHits(keyword, null, null, size);
            List<Long> ids = hits.stream().map(SearchHit::getId).toList();
            articles = ids.isEmpty() ? List.of() : articleMapper.selectPublicByIds(ids);
        }
        return articles.stream().map(this::toBrief).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<ArticleBrief> getHotPublic(String period, int limit) {
        String type = switch (period == null ? "view" : period.toLowerCase()) {
            case "like" -> "LIKE";
            case "favorite" -> "FAVORITE";
            case "comment" -> "COMMENT";
            default -> "VIEW";
        };
        int size = Math.min(Math.max(limit, 1), MAX_SEARCH);
        return articleMapper.selectTop(type, size).stream()
                .map(this::toBrief)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<CategoryVO> listCategories() {
        return taxonomyService.listPublicCategories();
    }

    public List<TagVO> listTags(int limit) {
        List<TagVO> tags = taxonomyService.listPublicTags();
        int size = Math.min(Math.max(limit, 1), tags.size());
        return tags.subList(0, size);
    }

    public Map<String, Object> getSiteOverview() {
        Map<String, Object> raw = articleMapper.selectOverview();
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> result.put(key, value));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ReactionTarget getReactionTarget(Long id) {
        if (id == null) {
            return new ReactionTarget(null, null, false, false);
        }
        Article article = articleMapper.selectBriefsByIds(List.of(id)).stream().findFirst().orElse(null);
        if (article == null || article.isDeleted() || article.getStatus() != ArticleStatus.PUBLISHED
                || article.getVisibility() == ArticleVisibility.PRIVATE) {
            return new ReactionTarget(id, null, false, false);
        }
        return new ReactionTarget(article.getId(), article.getAuthorId(), true,
                Boolean.TRUE.equals(article.getAllowComment()));
    }

    private ArticleBrief toBrief(Article article) {
        if (article == null) {
            return null;
        }
        return new ArticleBrief(article.getId(), article.getAuthorId(), article.getTitle(), article.getSlug(),
                article.getStatus(), article.getVisibility(),
                Boolean.TRUE.equals(article.getAllowComment()), article.isDeleted());
    }
}
