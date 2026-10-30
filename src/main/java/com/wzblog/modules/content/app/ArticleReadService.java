package com.wzblog.modules.content.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.api.CursorCodec;
import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.profile.UserBrief;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Ids;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.vo.ArchiveItemVO;
import com.wzblog.modules.content.api.vo.ArticleDetailVO;
import com.wzblog.modules.content.api.vo.ArticleEditVO;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.api.vo.AuthorVO;
import com.wzblog.modules.content.api.vo.RevisionVO;
import com.wzblog.modules.content.api.vo.SearchResultVO;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.ArticleRevision;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;
import com.wzblog.modules.content.infra.config.ContentProperties;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import com.wzblog.modules.content.infra.mapper.ArticleRevisionMapper;
import com.wzblog.modules.content.infra.mapper.SearchHit;
import com.wzblog.modules.identity.app.UserQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文章读取：信息流游标、搜索、详情缓存、归档、我的/后台列表（M2 §8）。
 */
@Service
public class ArticleReadService {

    private static final Logger log = LoggerFactory.getLogger(ArticleReadService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGE_SIZE = 100;

    private final ArticleMapper articleMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final ArticleAssembler articleAssembler;
    private final UserQueryService userQueryService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ContentProperties properties;

    public ArticleReadService(ArticleMapper articleMapper,
                              ArticleRevisionMapper revisionMapper,
                              ArticleAssembler articleAssembler,
                              UserQueryService userQueryService,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              ContentProperties properties) {
        this.articleMapper = articleMapper;
        this.revisionMapper = revisionMapper;
        this.articleAssembler = articleAssembler;
        this.userQueryService = userQueryService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CursorPage<ArticleListItemVO> feed(String keyword, String categoryId, String tagId,
                                              Integer year, Integer month, Boolean pinned,
                                              String cursor, Integer size) {
        if (Boolean.FALSE.equals(pinned)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "pinned 仅支持 true 或不传", null);
        }
        boolean pinnedOnly = Boolean.TRUE.equals(pinned);
        int limit;
        if (pinnedOnly) {
            int requested = size == null ? 3 : size;
            if (requested < 1 || requested > 10) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "pinned 模式下 size 需为 1~10", null);
            }
            limit = requested;
        } else {
            limit = pageSize(size, 20, 50);
        }
        if (keyword != null && keyword.length() > 50) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "keyword 不能超过 50 字符", null);
        }
        if (month != null && (year == null || month < 1 || month > 12)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "month 需同时指定 year 且为 1~12", null);
        }
        TimeRange range = timeRange(year, month);
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor);
        LocalDateTime cursorTime = decoded == null ? null : CursorCodec.toUtc(decoded);
        Long cursorId = decoded == null ? null : decoded.id();
        String keywordFilter = keyword == null || keyword.isBlank() ? null : escapeLike(keyword.trim());

        List<Article> rows = articleMapper.selectFeed(
                Ids.parseNullable(categoryId), Ids.parseNullable(tagId), keywordFilter,
                range == null ? null : range.from(), range == null ? null : range.to(),
                cursorTime, cursorId, pinnedOnly, limit + 1);
        boolean hasNext = rows.size() > limit;
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        String nextCursor = null;
        if (hasNext && !rows.isEmpty()) {
            Article last = rows.get(rows.size() - 1);
            nextCursor = CursorCodec.encode(last.getPublishedAt(), last.getId());
        }
        return CursorPage.of(articleAssembler.toListItems(rows), nextCursor, hasNext);
    }

    public SearchResultVO search(String keyword, String categoryId, String tagId, Integer size) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "keyword 不能为空", null);
        }
        String value = keyword.trim();
        if (value.length() > 50) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "keyword 不能超过 50 字符", null);
        }
        int limit = pageSize(size, 10, 20);
        Long category = Ids.parseNullable(categoryId);
        Long tag = Ids.parseNullable(tagId);
        // 与信息流统一为 title+summary 包含匹配（%kw%），按发布时间倒序，便于两接口对拍
        boolean degraded = value.codePointCount(0, value.length()) < 2;
        List<Article> rows = articleMapper.selectSearchLike(escapeLike(value), category, tag, limit);
        return new SearchResultVO(articleAssembler.toListItems(rows), degraded);
    }

    public List<ArticleListItemVO> pinned(int limit) {
        return articleAssembler.toListItems(articleMapper.selectPinned(Math.min(Math.max(limit, 1), 10)));
    }

    public List<ArticleListItemVO> top(String type, int limit) {
        String normalized = type == null ? "view" : type.toLowerCase(Locale.ROOT);
        String dbType = switch (normalized) {
            case "view" -> "VIEW";
            case "like" -> "LIKE";
            case "favorite" -> "FAVORITE";
            case "comment" -> "COMMENT";
            default -> throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "type 只能是 view/like/favorite/comment", null);
        };
        return articleAssembler.toListItems(articleMapper.selectTop(dbType, Math.min(Math.max(limit, 1), 50)));
    }

    public List<ArchiveItemVO> archive(Integer year) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "year 需在 2000~2100", null);
        }
        List<ArchiveItemVO> result = new ArrayList<>();
        if (year == null) {
            for (Map<String, Object> row : articleMapper.selectArchiveYears(BUSINESS_ZONE_OFFSET)) {
                result.add(new ArchiveItemVO(intValue(row, "y", "Y"), null, longValue(row, "cnt", "CNT")));
            }
        } else {
            for (Map<String, Object> row : articleMapper.selectArchiveMonths(year, BUSINESS_ZONE_OFFSET)) {
                result.add(new ArchiveItemVO(year, intValue(row, "m", "M"), longValue(row, "cnt", "CNT")));
            }
        }
        return result;
    }

    public ArticleDetailVO detail(String slug, CurrentUser currentUser) {
        Article article = articleMapper.selectBySlug(slug);
        assertVisible(article, currentUser);
        ArticleDetailVO cached = readCache(article);
        if (cached != null) {
            return withFreshCounters(cached, article);
        }
        ArticleDetailVO detail = articleAssembler.toDetail(article);
        writeCache(article, detail);
        return detail;
    }

    /**
     * 缓存体不承载易变计数：命中后用本次为可见性判断查出的新鲜行覆盖四个计数字段。
     * <p>计数 UPDATE 有意不递增 version（否则每次浏览都轮换键，命中率归零），
     * 因此版本键只保证内容/元数据的一致性；view/like/favorite/comment 计数
     * 永远来自当次 DB 读——这行数据反正是 detail() 必查的，覆盖近乎零成本。</p>
     */
    private ArticleDetailVO withFreshCounters(ArticleDetailVO cached, Article article) {
        boolean unchanged = Objects.equals(cached.viewCount(), article.getViewCount())
                && Objects.equals(cached.likeCount(), article.getLikeCount())
                && Objects.equals(cached.favoriteCount(), article.getFavoriteCount())
                && Objects.equals(cached.commentCount(), article.getCommentCount());
        if (unchanged) {
            return cached;
        }
        return new ArticleDetailVO(cached.id(), cached.title(), cached.slug(), cached.summary(),
                cached.coverKey(), cached.category(), cached.tags(), cached.author(),
                article.getViewCount(), article.getLikeCount(), article.getFavoriteCount(),
                article.getCommentCount(), cached.isPinned(), cached.readingSeconds(),
                cached.publishedAt(), cached.html(), cached.toc(), cached.allowComment(),
                cached.visibility(), cached.createdAt(), cached.updatedAt());
    }

    /** 编辑体只给作者本人和 ADMIN（ADMIN 只读，用于治理）；其他 EDITOR 按隐藏规则 403/404。 */
    public ArticleEditVO edit(CurrentUser currentUser, Long id) {
        Article article = requireReadableForAuthor(currentUser, id);
        return articleAssembler.toEdit(article);
    }

    public List<RevisionVO> revisions(CurrentUser currentUser, Long articleId) {
        Article article = requireReadableForAuthor(currentUser, articleId);
        List<ArticleRevision> revisions = revisionMapper.selectByArticleId(articleId);
        Set<Long> editorIds = new LinkedHashSet<>();
        for (ArticleRevision revision : revisions) {
            editorIds.add(revision.getEditorId());
        }
        Map<Long, UserBrief> editors = userQueryService.getBriefs(editorIds);
        List<RevisionVO> result = new ArrayList<>(revisions.size());
        for (ArticleRevision revision : revisions) {
            UserBrief editor = editors.get(revision.getEditorId());
            result.add(new RevisionVO(revision.getRevisionNo(),
                    editor == null ? null : new AuthorVO(editor.id(), editor.nickname(), editor.avatarKey()),
                    revision.getTitle(), revision.getSummary(), revision.getChangeNote(),
                    Times.toInstant(revision.getCreatedAt())));
        }
        return result;
    }

    public PageResult<ArticleListItemVO> me(CurrentUser currentUser, String status, int page, int pageSize) {
        int safePage = PageParams.requirePage(page);
        int safeSize = PageParams.requirePageSize(pageSize);
        String statusFilter = statusFilter(status);
        long total = articleMapper.countMe(currentUser.id(), statusFilter);
        List<Article> rows = articleMapper.selectMe(currentUser.id(), statusFilter,
                (long) (safePage - 1) * safeSize, safeSize);
        return PageResult.of(articleAssembler.toListItems(rows), total, safePage, safeSize);
    }

    public PageResult<ArticleListItemVO> admin(String status, String visibility, Boolean deleted,
                                               String keyword, String categoryId, String authorId,
                                               String sort, int page, int pageSize) {
        if (keyword != null && keyword.length() > 50) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "keyword 不能超过 50 字符", null);
        }
        int safePage = PageParams.requirePage(page);
        int safeSize = PageParams.requirePageSize(pageSize);
        String statusFilter = statusFilter(status);
        String visibilityFilter = visibilityFilter(visibility);
        String sortKey = switch (sort == null || sort.isBlank() ? "updatedAt" : sort) {
            case "updatedAt" -> "updatedAt";
            case "createdAt" -> "createdAt";
            case "publishedAt" -> "publishedAt";
            default -> throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "sort 只能是 updatedAt/createdAt/publishedAt", null);
        };
        Long category = Ids.parseNullable(categoryId);
        Long author = Ids.parseNullable(authorId);
        String keywordFilter = keyword == null ? null : escapeLike(keyword.trim());
        long total = articleMapper.countAdmin(statusFilter, visibilityFilter, deleted, keywordFilter, category, author);
        List<Article> rows = articleMapper.selectAdmin(statusFilter, visibilityFilter, deleted, keywordFilter,
                category, author, sortKey, (long) (safePage - 1) * safeSize, safeSize);
        return PageResult.of(articleAssembler.toListItems(rows), total, safePage, safeSize);
    }

    private String statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ArticleStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "status 只能是 DRAFT/PUBLISHED/ARCHIVED", null);
        }
    }

    private String visibilityFilter(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return null;
        }
        try {
            return ArticleVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "visibility 只能是 PUBLIC/PRIVATE/UNLISTED", null);
        }
    }

    /** 可见性（A 方案）：PRIVATE/未发布只有作者本人和 ADMIN 可见；其他 EDITOR 一律 404。 */
    private void assertVisible(Article article, CurrentUser currentUser) {
        if (article == null || article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        boolean ownerOrAdmin = isOwnerOrAdmin(article, currentUser);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            if (article.getVisibility() == ArticleVisibility.PRIVATE && !ownerOrAdmin) {
                throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            return;
        }
        if (!ownerOrAdmin) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
    }

    private Article requireReadableForAuthor(CurrentUser currentUser, Long id) {
        Article article = articleMapper.selectAnyById(id);
        if (article == null || article.isDeleted()) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!isOwnerOrAdmin(article, currentUser)) {
            boolean publiclyVisible = article.getStatus() == ArticleStatus.PUBLISHED
                    && article.getVisibility() == ArticleVisibility.PUBLIC;
            throw publiclyVisible
                    ? new BusinessException(ErrorCode.ARTICLE_FORBIDDEN)
                    : new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return article;
    }

    private boolean isOwnerOrAdmin(Article article, CurrentUser currentUser) {
        return currentUser != null
                && (currentUser.isAdmin() || Objects.equals(article.getAuthorId(), currentUser.id()));
    }

    private boolean isEditor(CurrentUser currentUser) {
        return currentUser != null
                && (currentUser.role() == Role.EDITOR || currentUser.role() == Role.ADMIN);
    }

    private double weightedScore(SearchHit hit, String keyword, Instant now) {
        double score = hit.getScore() == null ? 0 : hit.getScore();
        score += 3.0 * occurrences(hit.getTitle(), keyword);
        Instant publishedAt = hit.getPublishedAt() == null
                ? now : hit.getPublishedAt().toInstant(ZoneOffset.UTC);
        double days = Math.max(0, Duration.between(publishedAt, now).toDays());
        return score / (1.0 + days / 365.0);
    }

    private int occurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    private ArticleDetailVO readCache(Article article) {
        try {
            String json = redis.opsForValue().get(cacheKey(article));
            return json == null ? null : objectMapper.readValue(json, ArticleDetailVO.class);
        } catch (Exception ex) {
            log.warn("文章详情缓存读取失败，回源数据库, articleId={}", article.getId(), ex);
            return null;
        }
    }

    private void writeCache(Article article, ArticleDetailVO detail) {
        try {
            Duration base = properties.getDetailCacheTtl();
            long jitter = (long) (base.toMillis() * (0.9 + Math.random() * 0.2));
            redis.opsForValue().set(cacheKey(article), objectMapper.writeValueAsString(detail),
                    Duration.ofMillis(jitter));
        } catch (Exception ex) {
            log.warn("文章详情缓存写入失败, articleId={}", article.getId(), ex);
        }
    }

    private String cacheKey(Article article) {
        return "content:article:" + article.getId() + ":v" + article.getVersion();
    }

    private static final String BUSINESS_ZONE_OFFSET = "+08:00";

    /**
     * LIKE 包含匹配转义：! 为 ESCAPE 字符，% _ 按字面匹配；反斜杠在 ESCAPE '!' 下不再是转义符。
     */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * BOOLEAN MODE 关键词清洗：剥离布尔操作符与引号，防 MySQL 1064 语法错误（公开搜索接口 500）。
     */
    static String sanitizeSearchKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String cleaned = keyword.replace("-", " ")
                .replaceAll("[+*<>()~@\"]", " ")
                .replace("'", " ")
                .trim();
        return cleaned.isBlank() ? "" : cleaned;
    }

    private TimeRange timeRange(Integer year, Integer month) {
        if (year == null) {
            return null;
        }
        if (year < 2000 || year > 2100) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "year 需在 2000~2100", null);
        }
        LocalDate start = month == null ? LocalDate.of(year, 1, 1) : LocalDate.of(year, month, 1);
        ZonedDateTime fromZoned = start.atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime toZoned = (month == null ? start.plusYears(1) : start.plusMonths(1))
                .atStartOfDay(BUSINESS_ZONE);
        return new TimeRange(fromZoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                toZoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    private int pageSize(Integer size, int defaultValue, int max) {
        if (size == null) {
            return defaultValue;
        }
        if (size < 1 || size > max) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "size 需在 1~" + max, null);
        }
        return size;
    }

    private int intValue(Map<String, Object> row, String first, String second) {
        Object value = row.containsKey(first) ? row.get(first) : row.get(second);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long longValue(Map<String, Object> row, String first, String second) {
        Object value = row.containsKey(first) ? row.get(first) : row.get(second);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record TimeRange(LocalDateTime from, LocalDateTime to) {
    }
}
