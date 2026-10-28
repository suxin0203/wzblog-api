package com.wzblog.modules.interaction.app;

import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.content.app.ArticleCounterService;
import com.wzblog.modules.content.app.CounterType;
import com.wzblog.modules.interaction.domain.ReactionType;
import com.wzblog.modules.interaction.infra.mapper.ArticleReactionMapper;
import com.wzblog.modules.interaction.infra.mapper.CommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 互动计数对账（M3 §9）：每日 03:30 按明细重算文章三计数与评论点赞数，
 * 仅处理「近 10 分钟无明细写入」的文章，按差值经 M2 端口收敛并写审计。
 */
@Service
public class InteractionReconcileService {

    private static final Logger log = LoggerFactory.getLogger(InteractionReconcileService.class);
    private static final String LOCK_KEY = "wzblog:interaction:reconcile-lock";

    private final ArticleReactionMapper reactionMapper;
    private final com.wzblog.modules.interaction.infra.mapper.InteractionStatsMapper statsMapper;
    private final CommentMapper commentMapper;
    private final ArticleCounterService articleCounterService;
    private final AuditRecorder auditRecorder;
    private final StringRedisTemplate redis;

    public InteractionReconcileService(ArticleReactionMapper reactionMapper,
                                       com.wzblog.modules.interaction.infra.mapper.InteractionStatsMapper statsMapper,
                                       CommentMapper commentMapper,
                                       ArticleCounterService articleCounterService,
                                       AuditRecorder auditRecorder,
                                       StringRedisTemplate redis) {
        this.reactionMapper = reactionMapper;
        this.statsMapper = statsMapper;
        this.commentMapper = commentMapper;
        this.articleCounterService = articleCounterService;
        this.auditRecorder = auditRecorder;
        this.redis = redis;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        run();
    }

    public void run() {
        com.wzblog.common.lock.OwnerAwareRedisLock lock =
                new com.wzblog.common.lock.OwnerAwareRedisLock(redis, LOCK_KEY);
        if (!lock.tryAcquire(Duration.ofMinutes(30))) {
            log.info("互动对账跳过：锁被其他实例持有");
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);

            // 文章点赞/收藏按明细重算
            Map<Long, Map<CounterType, Long>> expected = new HashMap<>();
            for (var row : reactionMapper.countGroupedByArticle()) {
                CounterType type = "LIKE".equals(row.type()) ? CounterType.LIKE : CounterType.FAVORITE;
                expected.computeIfAbsent(row.articleId(), key -> new HashMap<>())
                        .put(type, row.cnt());
            }
            for (var row : commentMapper.countVisibleByArticleGrouped()) {
                expected.computeIfAbsent(row.articleId(), key -> new HashMap<>())
                        .put(CounterType.COMMENT, row.cnt());
            }

            // 候选 = 有明细的文章 ∪ 计数非零的文章（覆盖“计数漂移但无明细”）
            java.util.Set<Long> candidates = new java.util.HashSet<>(expected.keySet());
            candidates.addAll(articleCounterService.listArticleIdsWithCounters());

            int fixed = 0;
            for (Long articleId : candidates) {
                if (commentMapper.countRecentByArticle(articleId, since) > 0
                        || statsMapper.countRecentReactionsByArticle(articleId, since) > 0) {
                    continue; // 活跃文章跳过（评论与点赞/收藏窗口），避免与写入竞争
                }
                var counters = expected.getOrDefault(articleId, Map.of());
                for (CounterType type : CounterType.values()) {
                    long target = counters.getOrDefault(type, 0L); // 无明细的目标即 0
                    int current = articleCounterService.increment(articleId, type, 0);
                    if (current != target) {
                        int delta = (int) (target - current);
                        if (delta > 0) {
                            articleCounterService.increment(articleId, type, delta);
                        } else {
                            articleCounterService.decrement(articleId, type, -delta);
                        }
                        auditRecorder.record(null, "INTERACTION_RECONCILE", "ARTICLE",
                                String.valueOf(articleId),
                                "{\"" + type + "\":" + current + "}",
                                "{\"" + type + "\":" + target + "}");
                        fixed++;
                    }
                }
            }

            // 评论点赞数对账
            int commentFixed = 0;
            for (var row : commentMapper.countCommentLikes()) {
                long target = row.cnt();
                if (row.likeCount() != target) {
                    int delta = (int) (target - row.likeCount());
                    if (commentMapper.adjustLikeCount(row.id(), delta,
                            LocalDateTime.now(ZoneOffset.UTC)) > 0) {
                        auditRecorder.record(null, "INTERACTION_RECONCILE", "COMMENT",
                                String.valueOf(row.id()),
                                "{\"likeCount\":" + row.likeCount() + "}",
                                "{\"likeCount\":" + target + "}");
                        commentFixed++;
                    }
                }
            }
            log.info("互动对账完成 articleCountersFixed={} commentLikesFixed={}", fixed, commentFixed);

            var anomalies = commentMapper.findVisibilityAnomalies();
            if (!anomalies.isEmpty()) {
                log.warn("评论可见性异常 {} 条（首 10 条）：{}", anomalies.size(),
                        anomalies.subList(0, Math.min(10, anomalies.size())));
                auditRecorder.record(null, "INTERACTION_RECONCILE", "COMMENT", "VISIBILITY",
                        null, "{\"anomalies\":" + anomalies.size() + "}");
            }
        } finally {
            lock.release();
        }
    }
}
