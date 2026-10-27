package com.wzblog.modules.interaction.app;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.admin.app.SiteConfigService;
import com.wzblog.modules.content.app.ArticleCounterService;
import com.wzblog.modules.content.app.CounterType;
import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.app.ArticleQueryService;
import com.wzblog.modules.identity.app.UserQueryService;
import com.wzblog.common.profile.UserBrief;
import com.wzblog.modules.interaction.api.vo.AuthorVO;
import com.wzblog.modules.interaction.api.vo.CommentNodeVO;
import com.wzblog.modules.interaction.domain.Comment;
import com.wzblog.modules.interaction.domain.CommentStatus;
import com.wzblog.modules.interaction.domain.event.CommentApprovedEvent;
import com.wzblog.modules.interaction.domain.event.CommentRemovedEvent;
import com.wzblog.modules.interaction.infra.config.InteractionProperties;
import com.wzblog.modules.interaction.infra.mapper.CommentMapper;
import com.wzblog.modules.interaction.infra.redis.InteractionRateLimiter;
import com.wzblog.modules.interaction.infra.text.SensitiveWordFilter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 评论用例：两级树组装、敏感词、审核状态机、计数联动（经 M2 端口）与事件。
 */
@Service
public class CommentAppService {

    private static final int MAX_CHILDREN_PER_ROOT = 200;

    private final CommentMapper commentMapper;
    private final ArticleQueryService articleQueryService;
    private final ArticleCounterService articleCounterService;
    private final UserQueryService userQueryService;
    private final SiteConfigService siteConfigService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final InteractionRateLimiter rateLimiter;
    private final InteractionProperties interactionProperties;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    public CommentAppService(CommentMapper commentMapper,
                             ArticleQueryService articleQueryService,
                             ArticleCounterService articleCounterService,
                             UserQueryService userQueryService,
                             SiteConfigService siteConfigService,
                             SensitiveWordFilter sensitiveWordFilter,
                             InteractionRateLimiter rateLimiter,
                             InteractionProperties interactionProperties,
                             AuditRecorder auditRecorder,
                             ApplicationEventPublisher eventPublisher) {
        this.commentMapper = commentMapper;
        this.articleQueryService = articleQueryService;
        this.articleCounterService = articleCounterService;
        this.userQueryService = userQueryService;
        this.siteConfigService = siteConfigService;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.rateLimiter = rateLimiter;
        this.interactionProperties = interactionProperties;
        this.auditRecorder = auditRecorder;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------ 发表

    @Transactional
    public CommentNodeVO create(CurrentUser user, ClientInfo clientInfo, Long articleId,
                                String content, String parentId, String guestNickname) {
        ArticleBrief article = requireCommentableArticle(articleId);

        String normalized = validateContent(content, 2000);
        Long parent = parseId(parentId, "parentId");
        String guestName = user == null ? normalizeGuestName(guestNickname) : null;
        if (user == null && !siteConfigService.getBoolean("comment.guest_enabled", true)) {
            throw new BusinessException(ErrorCode.COMMENT_GUEST_DISABLED);
        }
        // 限流：登录按用户、游客按 IP
        String dimension = user != null ? "u:" + user.id() : "ip:" + clientInfo.ip();
        int shortWindow = user != null ? interactionProperties.getUserCommentShortWindow()
                : interactionProperties.getGuestCommentShortWindow();
        int shortLimit = user != null ? interactionProperties.getUserCommentShortLimit()
                : interactionProperties.getGuestCommentShortLimit();
        int dailyLimit = user != null ? interactionProperties.getUserCommentDailyLimit()
                : interactionProperties.getGuestCommentDailyLimit();
        rateLimiter.checkAndConsume("comment", dimension, shortWindow, shortLimit, dailyLimit);

        reloadSensitiveWords();
        var maskResult = sensitiveWordFilter.mask(normalized);
        String masked = maskResult.masked();
        boolean sensitive = maskResult.hit();
        boolean audit = siteConfigService.getBoolean("comment.audit_enabled", true);

        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(user == null ? null : user.id());
        comment.setGuestName(guestName);
        if (parent != null) {
            Comment parentComment = requireValidParent(articleId, parent);
            boolean parentIsRoot = parentComment.getRootId() == null;
            comment.setRootId(parentIsRoot ? parentComment.getId() : parentComment.getRootId());
            comment.setParentId(parentComment.getId());
            comment.setReplyToUserId(parentComment.getUserId());
        }
        comment.setContent(masked);
        comment.setStatus(sensitive || audit ? CommentStatus.PENDING : CommentStatus.APPROVED);
        comment.setLikeCount(0);
        comment.setIp(clientInfo.ip());
        comment.setUserAgent(truncate(clientInfo.userAgent(), 255));
        commentMapper.insert(comment);

        if (comment.getStatus() == CommentStatus.APPROVED) {
            articleCounterService.increment(articleId, CounterType.COMMENT, 1);
        }
        return toNode(comment, user == null ? null : Set.of(user.id()), Map.of(),
                false, 0).build();
    }

    // ------------------------------------------------------------ 读取

    @Transactional(readOnly = true)
    public CursorPage<CommentNodeVO> list(CurrentUser user, Long articleId, String cursor,
                                          int size, String rootIdParam) {
        requireCommentableArticle(articleId);
        Long viewerId = user == null ? null : user.id();
        if (rootIdParam != null && !rootIdParam.isBlank()) {
            return listChildren(articleId, parseId(rootIdParam, "rootId"), cursor, size);
        }

        int offset = CommentCursor.decode(cursor);
        int limit = Math.min(Math.max(size, 1), 50);
        List<Comment> roots = commentMapper.selectRootPage(articleId, viewerId, limit + 1, offset);
        boolean hasNext = roots.size() > limit;
        if (hasNext) {
            roots = roots.subList(0, limit);
        }
        String nextCursor = hasNext ? CommentCursor.encode(offset + limit) : null;

        List<Long> rootIds = roots.stream().map(Comment::getId).toList();
        Map<Long, List<Comment>> childrenByRoot = new HashMap<>();
        if (!rootIds.isEmpty()) {
            for (Comment child : commentMapper.selectChildrenOf(rootIds)) {
                childrenByRoot.computeIfAbsent(child.getRootId(), key -> new ArrayList<>()).add(child);
            }
        }
        Set<Long> viewerOnly = viewerId == null ? null : Set.of(viewerId);
        List<Comment> everyone = new ArrayList<>(roots);
        childrenByRoot.values().forEach(everyone::addAll);
        Map<Long, UserBrief> users = loadUsers(everyone);

        List<CommentNodeVO> nodes = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            List<Comment> children = childrenByRoot.getOrDefault(root.getId(), List.of());
            List<Comment> visibleChildren = children.size() > MAX_CHILDREN_PER_ROOT
                    ? children.subList(0, MAX_CHILDREN_PER_ROOT) : children;
            CommentNodeVO.CommentNodeVOBuilder builder = toNode(root, viewerOnly, users,
                    children.size() > MAX_CHILDREN_PER_ROOT, visibleChildren.size());
            List<CommentNodeVO> childNodes = new ArrayList<>(visibleChildren.size());
            for (Comment child : visibleChildren) {
                if (!child.isDeleted()) {
                    childNodes.add(toNode(child, viewerOnly, users, false, 0).build());
                }
            }
            builder.children(childNodes);
            nodes.add(builder.build());
        }
        return CursorPage.of(nodes, nextCursor, hasNext);
    }

    private CursorPage<CommentNodeVO> listChildren(Long articleId, Long rootId, String cursor,
                                                   int size) {
        Comment root = commentMapper.selectByIdIncludeDeleted(rootId);
        if (root == null || !Objects.equals(root.getArticleId(), articleId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        int offset = CommentCursor.decode(cursor);
        int limit = Math.min(Math.max(size, 1), 50);
        List<Comment> children = commentMapper.selectChildPage(rootId, limit + 1, offset);
        boolean hasNext = children.size() > limit;
        if (hasNext) {
            children = children.subList(0, limit);
        }
        String nextCursor = hasNext ? CommentCursor.encode(offset + limit) : null;
        Map<Long, UserBrief> users = loadUsers(children);
        List<CommentNodeVO> nodes = children.stream()
                .map(child -> toNode(child, Set.of(), users, false, 0).build())
                .toList();
        return CursorPage.of(nodes, nextCursor, hasNext);
    }

    // ------------------------------------------------------------ 删除与审核

    @Transactional
    public void deleteByOwner(CurrentUser user, Long commentId) {
        Comment comment = requireExisting(commentId);
        if (user.role() != Role.ADMIN && !Objects.equals(comment.getUserId(), user.id())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (comment.isDeleted()) {
            return; // 幂等
        }
        if (commentMapper.softDelete(commentId, Times.nowUtc()) == 0) {
            return;
        }
        if (comment.getStatus() == CommentStatus.APPROVED && comment.getUserId() != null) {
            articleCounterService.decrement(comment.getArticleId(), CounterType.COMMENT, 1);
            eventPublisher.publishEvent(new CommentRemovedEvent(commentId,
                    comment.getArticleId(), comment.getUserId()));
        }
    }

    @Transactional
    public CommentStatus moderate(CurrentUser editor, Long commentId, CommentStatus target,
                                  String remark) {
        if (target == CommentStatus.PENDING) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标状态不能为 PENDING",
                    null);
        }
        Comment comment = requireExisting(commentId);
        if (comment.getStatus() == target) {
            return target; // 幂等
        }
        boolean allowed = switch (comment.getStatus()) {
            case PENDING -> target == CommentStatus.APPROVED || target == CommentStatus.REJECTED;
            case APPROVED -> target == CommentStatus.HIDDEN;
            case REJECTED, HIDDEN -> target == CommentStatus.APPROVED;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.COMMENT_STATUS_INVALID);
        }
        if (commentMapper.transitionStatus(commentId, comment.getStatus(), target,
                Times.nowUtc()) == 0) {
            throw new BusinessException(ErrorCode.COMMENT_STATUS_INVALID);
        }
        auditRecorder.record(editor, "COMMENT_STATUS", "COMMENT", String.valueOf(commentId),
                "{\"status\":\"" + comment.getStatus() + "\"}",
                "{\"status\":\"" + target + "\",\"remark\":" + quote(remark) + "}");

        if (target == CommentStatus.APPROVED && comment.getStatus() != CommentStatus.APPROVED) {
            articleCounterService.increment(comment.getArticleId(), CounterType.COMMENT, 1);
            if (comment.getUserId() != null) {
                ArticleBrief article = articleQueryService.getBrief(comment.getArticleId());
                eventPublisher.publishEvent(new CommentApprovedEvent(commentId,
                        comment.getArticleId(), comment.getUserId(),
                        article == null ? "" : article.title()));
            }
        } else if (comment.getStatus() == CommentStatus.APPROVED && target != CommentStatus.APPROVED) {
            articleCounterService.decrement(comment.getArticleId(), CounterType.COMMENT, 1);
            if (comment.getUserId() != null) {
                eventPublisher.publishEvent(new CommentRemovedEvent(commentId,
                        comment.getArticleId(), comment.getUserId()));
            }
        }
        return target;
    }

    @Transactional
    public void deleteByAdmin(CurrentUser editor, Long commentId) {
        Comment comment = requireExisting(commentId);
        if (comment.isDeleted()) {
            return;
        }
        if (commentMapper.softDelete(commentId, Times.nowUtc()) > 0) {
            auditRecorder.record(editor, "COMMENT_STATUS", "COMMENT", String.valueOf(commentId),
                    "{\"status\":\"" + comment.getStatus() + "\"}", "{\"deleted\":true}");
            if (comment.getStatus() == CommentStatus.APPROVED) {
                articleCounterService.decrement(comment.getArticleId(), CounterType.COMMENT, 1);
                if (comment.getUserId() != null) {
                    eventPublisher.publishEvent(new CommentRemovedEvent(commentId,
                            comment.getArticleId(), comment.getUserId()));
                }
            }
        }
    }

    public com.wzblog.common.api.PageResult<CommentNodeVO> adminList(CommentStatus status,
                                                                     Long articleId, int page,
                                                                     int pageSize) {
        long total = commentMapper.countAdminPage(status, articleId);
        int offset = (page - 1) * pageSize;
        List<Comment> comments = commentMapper.selectAdminPage(status, articleId, pageSize, offset);
        Map<Long, UserBrief> users = loadUsers(comments);
        Map<Long, ArticleBrief> articles = loadArticles(comments);
        List<CommentNodeVO> list = comments.stream()
                .map(comment -> toNode(comment, null, users, false, 0)
                        .articleTitle(articles.containsKey(comment.getArticleId())
                                ? articles.get(comment.getArticleId()).title() : null)
                        .ip(comment.getIp())
                        .build())
                .toList();
        return new com.wzblog.common.api.PageResult<>(list, total, page, pageSize,
                offset + list.size() < total);
    }

    // ------------------------------------------------------------ 内部

    private ArticleBrief requireCommentableArticle(Long articleId) {
        ArticleBrief article = articleQueryService.getBrief(articleId);
        if (article == null || !article.publiclyVisible()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (!article.allowComment()) {
            throw new BusinessException(ErrorCode.COMMENT_DISABLED);
        }
        return article;
    }

    private Comment requireValidParent(Long articleId, Long parentId) {
        Comment parent = commentMapper.selectByIdIncludeDeleted(parentId);
        if (parent == null || parent.isDeleted()
                || !Objects.equals(parent.getArticleId(), articleId)
                || parent.getStatus() != CommentStatus.APPROVED) {
            throw new BusinessException(ErrorCode.COMMENT_PARENT_INVALID);
        }
        return parent;
    }

    private Comment requireExisting(Long commentId) {
        Comment comment = commentMapper.selectByIdIncludeDeleted(commentId);
        if (comment == null || comment.isDeleted()) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }

    private String validateContent(String content, int maxCodePoints) {
        if (content == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "content 不能为空", null);
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty() || trimmed.codePoints().count() > maxCodePoints) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "content 长度须为 1~" + maxCodePoints + " 字符", null);
        }
        return trimmed;
    }

    private String normalizeGuestName(String guestNickname) {
        if (guestNickname == null || guestNickname.isBlank()) {
            return "匿名";
        }
        String trimmed = guestNickname.trim();
        long codePoints = trimmed.codePoints().count();
        if (codePoints < 1 || codePoints > 30) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "guestNickname 长度须为 1~30 字符", null);
        }
        return trimmed;
    }

    private void reloadSensitiveWords() {
        // 站点配置缓存 60s，这里同步刷新词库（词库通常很小）
        sensitiveWordFilter.reload(siteConfigService.getStringList("comment.sensitive_words"));
    }

    private Map<Long, UserBrief> loadUsers(List<Comment> comments) {
        var ids = comments.stream().map(Comment::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, UserBrief> users = new HashMap<>();
        if (!ids.isEmpty()) {
            users.putAll(userQueryService.getBriefs(ids));
        }
        for (Comment comment : comments) {
            if (comment.getReplyToUserId() != null && !users.containsKey(comment.getReplyToUserId())) {
                UserBrief brief = userQueryService.getBrief(comment.getReplyToUserId());
                if (brief != null) {
                    users.put(brief.id(), brief);
                }
            }
        }
        return users;
    }

    private Map<Long, ArticleBrief> loadArticles(List<Comment> comments) {
        var ids = comments.stream().map(Comment::getArticleId).distinct().toList();
        Map<Long, ArticleBrief> articles = new LinkedHashMap<>();
        for (ArticleBrief brief : articleQueryService.getBriefs(ids)) {
            articles.put(brief.id(), brief);
        }
        return articles;
    }

    private CommentNodeVO.CommentNodeVOBuilder toNode(Comment comment, Set<Long> viewerIds,
                                                      Map<Long, UserBrief> users,
                                                      boolean hasMoreReplies, int replyCount) {
        boolean deleted = comment.isDeleted();
        UserBrief author = comment.getUserId() == null ? null : users.get(comment.getUserId());
        AuthorVO authorVO = deleted || author == null
                ? new AuthorVO(null, comment.getGuestName() == null ? "匿名" : comment.getGuestName(),
                        null, comment.getUserId() == null)
                : new AuthorVO(author.id(), author.nickname(), author.avatarKey(), false);
        AuthorVO replyTo = null;
        if (comment.getReplyToUserId() != null) {
            UserBrief target = users.get(comment.getReplyToUserId());
            replyTo = new AuthorVO(comment.getReplyToUserId(),
                    target == null ? "已注销用户" : target.nickname(), null, false);
        }
        boolean isSelf = viewerIds != null && comment.getUserId() != null
                && viewerIds.contains(comment.getUserId());
        return CommentNodeVO.builder()
                .id(String.valueOf(comment.getId()))
                .articleId(String.valueOf(comment.getArticleId()))
                .rootId(comment.getRootId() == null ? null : String.valueOf(comment.getRootId()))
                .parentId(comment.getParentId() == null ? null : String.valueOf(comment.getParentId()))
                .author(authorVO)
                .replyToUser(replyTo)
                .content(deleted ? null : comment.getContent())
                .status(isSelf || viewerIds == null ? comment.getStatus().name() : null)
                .likeCount(deleted ? 0 : comment.getLikeCount())
                .liked(false)
                .replyCount(replyCount)
                .hasMoreReplies(hasMoreReplies)
                .deleted(deleted)
                .createdAt(Times.toInstant(comment.getCreatedAt()));
    }

    private static Long parseId(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, field + " 非法", null);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 评论游标：Base64URL 编码的偏移量（与 Mapper 的 OFFSET 分页一致，对外不透明）。 */
    static final class CommentCursor {

        static String encode(int offset) {
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("o=" + offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static int decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return 0;
            }
            try {
                String value = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!value.startsWith("o=")) {
                    throw new IllegalArgumentException();
                }
                int offset = Integer.parseInt(value.substring(2));
                if (offset < 0) {
                    throw new IllegalArgumentException();
                }
                return offset;
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "cursor 非法", null);
            }
        }
    }
}
