package com.wzblog.modules.points.app;

import com.wzblog.modules.identity.domain.event.UserLoggedInEvent;
import com.wzblog.modules.content.domain.event.ArticleDeletedEvent;
import com.wzblog.modules.content.domain.event.ArticlePublishedEvent;
import com.wzblog.modules.interaction.domain.event.CommentApprovedEvent;
import com.wzblog.modules.interaction.domain.event.CommentRemovedEvent;
import com.wzblog.modules.interaction.domain.event.ReactionToggledEvent;
import com.wzblog.modules.points.domain.PointsType;
import com.wzblog.modules.points.infra.config.PointsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 积分事件发放（M4 §10.1）：AFTER_COMMIT 后独立事务记账，失败只记日志不重试。
 * <p>幂等由各类型的 idempotency_key 承担；重复投递返回 duplicate 结果。</p>
 */
@Component
public class PointsEventListener {

    private static final Logger log = LoggerFactory.getLogger(PointsEventListener.class);

    private final PointsAppService pointsAppService;
    private final PointsProperties properties;

    public PointsEventListener(PointsAppService pointsAppService, PointsProperties properties) {
        this.pointsAppService = pointsAppService;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserLoggedIn(UserLoggedInEvent event) {
        try {
            if (!event.firstLoginOfDay()) {
                return;
            }
            String businessDate = PointsAppService.BusinessDayWindow.businessDate(event.loginAt());
            // 每日 1 次由幂等键保证，不设日上限（M4 §6.1）
            pointsAppService.grant(event.userId(), PointsType.DAILY_LOGIN,
                    properties.getDailyLoginAmount(), 0, "SYSTEM",
                    businessDate, "login:" + event.userId() + ":" + businessDate, "每日登录");
        } catch (Exception ex) {
            log.warn("DAILY_LOGIN 积分失败 userId={}", event.userId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArticlePublished(ArticlePublishedEvent event) {
        try {
            pointsAppService.grant(event.authorId(), PointsType.ARTICLE_PUBLISH,
                    properties.getArticlePublishAmount(), properties.getArticlePublishDailyCap(),
                    "ARTICLE", String.valueOf(event.articleId()),
                    "publish:" + event.articleId(), "发布文章：" + truncate(event.title()));
        } catch (Exception ex) {
            log.warn("ARTICLE_PUBLISH 积分失败 articleId={}", event.articleId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        try {
            pointsAppService.reverse(event.authorId(), PointsType.ARTICLE_PUBLISH_REVERSAL,
                    PointsType.ARTICLE_PUBLISH, "ARTICLE", String.valueOf(event.articleId()),
                    "article_delete:" + event.articleId());
        } catch (Exception ex) {
            log.warn("ARTICLE_PUBLISH_REVERSAL 失败 articleId={}", event.articleId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentApproved(CommentApprovedEvent event) {
        try {
            if (event.commenterId() == null) {
                return; // 游客评论无接收人
            }
            pointsAppService.grant(event.commenterId(), PointsType.COMMENT_APPROVED,
                    properties.getCommentApprovedAmount(), properties.getCommentApprovedDailyCap(),
                    "COMMENT", String.valueOf(event.commentId()),
                    "comment_approved:" + event.commentId(), "评论过审");
        } catch (Exception ex) {
            log.warn("COMMENT_APPROVED 积分失败 commentId={}", event.commentId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentRemoved(CommentRemovedEvent event) {
        try {
            if (event.commenterId() == null) {
                return;
            }
            pointsAppService.reverse(event.commenterId(), PointsType.COMMENT_APPROVED_REVERSAL,
                    PointsType.COMMENT_APPROVED, "COMMENT", String.valueOf(event.commentId()),
                    "comment_removed:" + event.commentId());
        } catch (Exception ex) {
            log.warn("COMMENT_APPROVED_REVERSAL 失败 commentId={}", event.commentId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReactionToggled(ReactionToggledEvent event) {
        try {
            if (!event.isLike() || event.authorId() == null) {
                return;
            }
            if (event.active()) {
                pointsAppService.post(event.authorId(), PointsType.LIKE_RECEIVED,
                        properties.getLikeReceivedAmount(), "ARTICLE",
                        String.valueOf(event.articleId()),
                        "like:" + event.articleId() + ":" + event.actorId(), "收到点赞", null);
            } else {
                // 精确反转该 actor 的点赞积分（biz 汇总会把其他点赞者一起扣掉）
                pointsAppService.reverseExact(event.authorId(), PointsType.LIKE_REVERSED,
                        "like:" + event.articleId() + ":" + event.actorId(),
                        "unlike:" + event.articleId() + ":" + event.actorId(),
                        "ARTICLE", String.valueOf(event.articleId()));
            }
        } catch (Exception ex) {
            log.warn("LIKE 积分失败 articleId={} actorId={}", event.articleId(), event.actorId(),
                    ex);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.codePoints().count() <= 50 ? value
                : new String(value.codePoints().limit(50).toArray(), 0, 50);
    }
}
