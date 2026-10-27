package com.wzblog.modules.interaction.app;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.app.ArticleCounterService;
import com.wzblog.modules.content.app.CounterType;
import com.wzblog.modules.content.app.ArticleQueryService;
import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.interaction.api.vo.CommentLikeVO;
import com.wzblog.modules.interaction.api.vo.MyReactionItemVO;
import com.wzblog.modules.interaction.api.vo.MyReactionVO;
import com.wzblog.modules.interaction.api.vo.ReactionVO;
import com.wzblog.modules.interaction.domain.Comment;
import com.wzblog.modules.interaction.domain.CommentStatus;
import com.wzblog.modules.interaction.domain.ReactionType;
import com.wzblog.modules.interaction.domain.event.ReactionToggledEvent;
import com.wzblog.modules.interaction.infra.mapper.ArticleReactionMapper;
import com.wzblog.modules.interaction.infra.mapper.CommentLikeMapper;
import com.wzblog.modules.interaction.infra.mapper.CommentMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 点赞/收藏：幂等 PUT/DELETE、uk_target 兜底并发、计数经 M2 端口同事务维护。
 */
@Service
public class ReactionAppService {

    private final ArticleReactionMapper reactionMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentMapper commentMapper;
    private final ArticleQueryService articleQueryService;
    private final ArticleCounterService articleCounterService;
    private final NotificationAppService notificationAppService;
    private final ApplicationEventPublisher eventPublisher;

    public ReactionAppService(ArticleReactionMapper reactionMapper,
                              CommentLikeMapper commentLikeMapper,
                              CommentMapper commentMapper,
                              ArticleQueryService articleQueryService,
                              ArticleCounterService articleCounterService,
                              NotificationAppService notificationAppService,
                              ApplicationEventPublisher eventPublisher) {
        this.reactionMapper = reactionMapper;
        this.commentLikeMapper = commentLikeMapper;
        this.commentMapper = commentMapper;
        this.articleQueryService = articleQueryService;
        this.articleCounterService = articleCounterService;
        this.notificationAppService = notificationAppService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ReactionVO setReaction(CurrentUser user, Long articleId, ReactionType type) {
        requireReactableArticle(articleId);
        int inserted = reactionMapper.insertIgnore(articleId, user.id(), type);
        if (inserted > 0) {
            articleCounterService.increment(articleId, toCounterType(type), 1);
            if (type == ReactionType.LIKE) {
                ArticleBrief article = articleQueryService.getBrief(articleId);
                eventPublisher.publishEvent(new ReactionToggledEvent(articleId,
                        article == null ? null : article.authorId(), user.id(), type, true));
            } else {
                // 收藏通知在本模块 AFTER_COMMIT 写（不走跨模块事件，M3 §8）
                long actorId = user.id();
                registerAfterCommit(() -> {
                    ArticleBrief article = articleQueryService.getBrief(articleId);
                    if (article != null && article.authorId() != null) {
                        notificationAppService.create(article.authorId(), actorId,
                                "ARTICLE_FAVORITED", "有人收藏了你的文章", "",
                                "ARTICLE", String.valueOf(articleId),
                                "article_favorited:" + articleId + ":" + actorId);
                    }
                });
            }
        }
        long count = reactionMapper.countByArticle(articleId, type);
        return new ReactionVO(String.valueOf(articleId), type.name(), true, count);
    }

    @Transactional
    public ReactionVO unsetReaction(CurrentUser user, Long articleId, ReactionType type) {
        requireReactableArticle(articleId);
        int deleted = reactionMapper.deleteExact(articleId, user.id(), type);
        if (deleted > 0) {
            articleCounterService.decrement(articleId, toCounterType(type), 1);
            if (type == ReactionType.LIKE) {
                ArticleBrief article = articleQueryService.getBrief(articleId);
                eventPublisher.publishEvent(new ReactionToggledEvent(articleId,
                        article == null ? null : article.authorId(), user.id(), type, false));
            }
        }
        long count = reactionMapper.countByArticle(articleId, type);
        return new ReactionVO(String.valueOf(articleId), type.name(), false, count);
    }

    @Transactional(readOnly = true)
    public MyReactionVO myStatus(CurrentUser user, Long articleId) {
        requireReactableArticle(articleId);
        if (user == null) {
            return new MyReactionVO(String.valueOf(articleId), false, false,
                    reactionMapper.countByArticle(articleId, ReactionType.LIKE),
                    reactionMapper.countByArticle(articleId, ReactionType.FAVORITE));
        }
        return new MyReactionVO(String.valueOf(articleId),
                reactionMapper.exists(articleId, user.id(), ReactionType.LIKE) > 0,
                reactionMapper.exists(articleId, user.id(), ReactionType.FAVORITE) > 0,
                reactionMapper.countByArticle(articleId, ReactionType.LIKE),
                reactionMapper.countByArticle(articleId, ReactionType.FAVORITE));
    }

    @Transactional(readOnly = true)
    public CursorPage<MyReactionItemVO> myList(CurrentUser user, ReactionType type, String cursor,
                                               int size) {
        int offset = CommentAppService.CommentCursor.decode(cursor);
        int limit = Math.min(Math.max(size, 1), 50);
        var reactions = reactionMapper.selectMyPage(user.id(), type, limit + 1, offset);
        boolean hasNext = reactions.size() > limit;
        if (hasNext) {
            reactions = reactions.subList(0, limit);
        }
        String nextCursor = hasNext ? CommentAppService.CommentCursor.encode(offset + limit) : null;

        List<Long> articleIds = reactions.stream().map(r -> r.getArticleId()).toList();
        Map<Long, ArticleBrief> articles = new java.util.HashMap<>();
        for (ArticleBrief brief : articleQueryService.getBriefs(articleIds)) {
            articles.put(brief.id(), brief);
        }
        List<MyReactionItemVO> items = new ArrayList<>();
        for (var reaction : reactions) {
            ArticleBrief article = articles.get(reaction.getArticleId());
            if (article == null || !article.publiclyVisible()) {
                continue; // 软删/下架文章过滤
            }
            items.add(new MyReactionItemVO(String.valueOf(article.id()), article.title(),
                    article.slug(), null, Times.toInstant(reaction.getCreatedAt())));
        }
        return CursorPage.of(items, nextCursor, hasNext);
    }

    // ------------------------------------------------------------ 评论点赞

    @Transactional
    public CommentLikeVO likeComment(CurrentUser user, Long commentId) {
        Comment comment = requireVisibleComment(commentId);
        int inserted = commentLikeMapper.insertIgnore(commentId, user.id());
        if (inserted > 0) {
            commentMapper.adjustLikeCount(commentId, 1, Times.nowUtc());
            long actorId = user.id();
            Long commentAuthor = comment.getUserId();
            if (commentAuthor != null) {
                // 游客评论（userId=null）无通知接收人，跳过
                registerAfterCommit(() -> notificationAppService.create(commentAuthor, actorId,
                        "COMMENT_LIKE", "有人赞了你的评论", "", "COMMENT",
                        String.valueOf(commentId), "comment_like:" + commentId + ":" + actorId));
            }
        }
        return new CommentLikeVO(String.valueOf(commentId), true, currentLikeCount(comment));
    }

    @Transactional
    public CommentLikeVO unlikeComment(CurrentUser user, Long commentId) {
        Comment comment = requireVisibleComment(commentId);
        int deleted = commentLikeMapper.deleteExact(commentId, user.id());
        if (deleted > 0) {
            commentMapper.adjustLikeCount(commentId, -1, Times.nowUtc());
        }
        return new CommentLikeVO(String.valueOf(commentId), false, currentLikeCount(comment));
    }

    // ------------------------------------------------------------ 内部

    private int currentLikeCount(Comment comment) {
        Comment fresh = commentMapper.selectByIdIncludeDeleted(comment.getId());
        return fresh == null || fresh.isDeleted() ? 0 : fresh.getLikeCount();
    }

    private Comment requireVisibleComment(Long commentId) {
        Comment comment = commentMapper.selectByIdIncludeDeleted(commentId);
        if (comment == null || comment.isDeleted()
                || comment.getStatus() != CommentStatus.APPROVED) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }

    private void requireReactableArticle(Long articleId) {
        ArticleBrief article = articleQueryService.getBrief(articleId);
        if (article == null || !article.publiclyVisible()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
    }

    private CounterType toCounterType(ReactionType type) {
        return type == ReactionType.LIKE ? CounterType.LIKE : CounterType.FAVORITE;
    }

    private void registerAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                action.run();
                            } catch (Exception ex) {
                                org.slf4j.LoggerFactory.getLogger(ReactionAppService.class)
                                        .warn("提交后通知失败", ex);
                            }
                        }
                    });
        } else {
            action.run();
        }
    }
}
