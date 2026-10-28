package com.wzblog.modules.interaction.app;

import com.wzblog.modules.content.app.port.ArticleBrief;
import com.wzblog.modules.content.app.ArticleQueryService;
import com.wzblog.modules.interaction.domain.Comment;
import com.wzblog.modules.points.domain.event.OrderStatusChangedEvent;
import com.wzblog.modules.points.domain.event.PointsAdjustedEvent;
import com.wzblog.modules.content.domain.event.ArticleModeratedEvent;
import com.wzblog.modules.interaction.domain.event.CommentApprovedEvent;
import com.wzblog.modules.interaction.domain.event.CommentRemovedEvent;
import com.wzblog.modules.interaction.domain.event.ReactionToggledEvent;
import com.wzblog.modules.interaction.infra.mapper.CommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 互动事件 → 站内通知（AFTER_COMMIT；失败只记日志不回滚主流程，M3 §11.1）。
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationAppService notificationAppService;
    private final CommentMapper commentMapper;
    private final ArticleQueryService articleQueryService;

    public NotificationEventListener(NotificationAppService notificationAppService,
                                     CommentMapper commentMapper,
                                     ArticleQueryService articleQueryService) {
        this.notificationAppService = notificationAppService;
        this.commentMapper = commentMapper;
        this.articleQueryService = articleQueryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentApproved(CommentApprovedEvent event) {
        try {
            notificationAppService.create(event.commenterId(), null, "COMMENT_APPROVED",
                    "你的评论已通过审核", summary(event.articleTitle()),
                    "COMMENT", String.valueOf(event.commentId()),
                    "comment_approved:" + event.commentId());

            ArticleBrief article = articleQueryService.getBrief(event.articleId());
            Long articleAuthor = article == null ? null : article.authorId();
            if (articleAuthor == null) {
                return;
            }
            Comment comment = commentMapper.selectByIdIncludeDeleted(event.commentId());
            if (comment == null) {
                return;
            }
            if (comment.getRootId() == null) {
                notificationAppService.create(articleAuthor, event.commenterId(),
                        "ARTICLE_COMMENTED", "你的文章收到了新评论",
                        summary(event.articleTitle()), "ARTICLE", String.valueOf(event.articleId()),
                        "article_commented:" + event.commentId());
            } else {
                Comment parent = comment.getParentId() == null ? null
                        : commentMapper.selectByIdIncludeDeleted(comment.getParentId());
                Long replyTarget = parent == null || parent.getUserId() == null
                        ? articleAuthor : parent.getUserId();
                notificationAppService.create(replyTarget, event.commenterId(), "COMMENT_REPLY",
                        "你的评论收到了回复", summary(comment.getContent()),
                        "COMMENT", String.valueOf(event.commentId()),
                        "comment_reply:" + event.commentId());
            }
        } catch (Exception ex) {
            log.warn("评论过审通知失败 commentId={}", event.commentId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReactionToggled(ReactionToggledEvent event) {
        try {
            if (!event.active() || event.authorId() == null) {
                return; // 取消点赞不通知
            }
            notificationAppService.create(event.authorId(), event.actorId(), "ARTICLE_LIKED",
                    "有人赞了你的文章", "", "ARTICLE", String.valueOf(event.articleId()),
                    "article_liked:" + event.articleId() + ":" + event.actorId());
        } catch (Exception ex) {
            log.warn("点赞通知失败 articleId={}", event.articleId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentRemoved(CommentRemovedEvent event) {
        // 下架/删除不发通知（避免打扰），仅保留钩子供 M4 反向积分
    }

    /** 治理通知（A 方案 P0，复用 SYSTEM 类型，0 迁移）：下架/恢复/彻底删除/接管告知原作者。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArticleModerated(ArticleModeratedEvent event) {
        try {
            if (event.authorId() == null || event.authorId().equals(event.moderatorId())) {
                return;
            }
            String title = switch (event.action()) {
                case "SOFT_DELETE" -> "你的文章已被管理员移入回收站";
                case "RESTORE" -> "你的文章已从回收站恢复";
                case "PURGE" -> "你的文章已被彻底删除";
                case "TAKEOVER" -> "你的文章已被站主接管";
                default -> "你的文章状态发生变更";
            };
            String reason = event.reason() == null || event.reason().isBlank()
                    ? "" : "原因：" + event.reason();
            notificationAppService.create(event.authorId(), event.moderatorId(), "SYSTEM",
                    title, reason, "ARTICLE", String.valueOf(event.articleId()),
                    "article_moderated:" + event.articleId() + ":" + event.action()
                            + ":" + event.version());
        } catch (Exception ex) {
            log.warn("文章治理通知失败 articleId={} action={}", event.articleId(), event.action(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        try {
            String type = switch (event.toStatus()) {
                case "APPROVED" -> "ORDER_APPROVED";
                case "SHIPPED" -> "ORDER_SHIPPED";
                case "COMPLETED" -> "ORDER_COMPLETED";
                case "REJECTED" -> "ORDER_REJECTED";
                case "CANCELLED" -> "ORDER_CANCELLED";
                default -> null;
            };
            if (type == null) {
                return;
            }
            notificationAppService.create(event.userId(), null, type,
                    "订单状态更新：" + event.toStatus(), "订单号 " + event.orderNo(),
                    "ORDER", event.orderNo(), "order:" + event.orderNo() + ":" + event.toStatus());
        } catch (Exception ex) {
            log.warn("订单通知失败 orderNo={}", event.orderNo(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPointsAdjusted(PointsAdjustedEvent event) {
        try {
            notificationAppService.create(event.userId(), null, "POINTS_ADJUSTED",
                    "积分调整：" + (event.amount() > 0 ? "+" : "") + event.amount(),
                    "当前余额 " + event.balanceAfter() + "；" + event.reason(),
                    "POINTS", null, "points:" + event.ledgerId());
        } catch (Exception ex) {
            log.warn("积分通知失败 userId={}", event.userId(), ex);
        }
    }

    private static String summary(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.codePoints().count() <= 100 ? value
                : new String(value.codePoints().limit(100).toArray(), 0, 100);
    }
}
