package com.wzblog.modules.admin.app;

import com.wzblog.modules.admin.domain.DailyStat;
import com.wzblog.modules.admin.infra.mapper.DailyStatsMapper;
import com.wzblog.modules.ai.app.AiSessionAppService;
import com.wzblog.modules.content.app.ArticleStatsService;
import com.wzblog.modules.content.app.port.DailyViews;
import com.wzblog.modules.identity.app.UserQueryService;
import com.wzblog.modules.interaction.app.InteractionStatsService;
import com.wzblog.modules.points.app.PointsQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 日统计预聚合（M7 §5）：每日 03:10 回填昨日，读取侧只查 daily_stats。
 * 各模块数据一律经 app 端口获取，不跨模块查表。
 */
@Service
public class DailyStatsAggregateJob {

    private static final Logger log = LoggerFactory.getLogger(DailyStatsAggregateJob.class);
    private static final String LOCK_KEY = "wzblog:admin:daily-stats-lock";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DailyStatsMapper dailyStatsMapper;
    private final UserQueryService userQueryService;
    private final com.wzblog.modules.content.app.ArticleQueryService articleQueryService;
    private final ArticleStatsService articleStatsService;
    private final InteractionStatsService interactionStatsService;
    private final PointsQueryService pointsQueryService;
    private final AiSessionAppService aiSessionAppService;
    private final StringRedisTemplate redis;

    public DailyStatsAggregateJob(DailyStatsMapper dailyStatsMapper,
                                  UserQueryService userQueryService,
                                  com.wzblog.modules.content.app.ArticleQueryService articleQueryService,
                                  ArticleStatsService articleStatsService,
                                  InteractionStatsService interactionStatsService,
                                  PointsQueryService pointsQueryService,
                                  AiSessionAppService aiSessionAppService,
                                  StringRedisTemplate redis) {
        this.dailyStatsMapper = dailyStatsMapper;
        this.userQueryService = userQueryService;
        this.articleQueryService = articleQueryService;
        this.articleStatsService = articleStatsService;
        this.interactionStatsService = interactionStatsService;
        this.pointsQueryService = pointsQueryService;
        this.aiSessionAppService = aiSessionAppService;
        this.redis = redis;
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        run(LocalDate.now(BUSINESS_ZONE).minusDays(1));
    }

    public void run(LocalDate statDate) {
        com.wzblog.common.lock.OwnerAwareRedisLock lock =
                new com.wzblog.common.lock.OwnerAwareRedisLock(redis, LOCK_KEY);
        if (!lock.tryAcquire(Duration.ofMinutes(30))) {
            return;
        }
        try {
            LocalDateTime from = statDate.atStartOfDay(BUSINESS_ZONE)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime to = from.plusDays(1);

            DailyStat stat = new DailyStat();
            stat.setStatDate(statDate);
            stat.setUserRegistered((int) userQueryService.countRegisteredBetween(from, to));
            stat.setArticlePublished((int) articleQueryService.countPublishedBetween(from, to));
            stat.setCommentCreated((int) interactionStatsService.countCommentsCreatedBetween(from, to));
            stat.setLikeCount((int) interactionStatsService.countReactionsBetween("LIKE", from, to));
            stat.setFavoriteCount((int) interactionStatsService.countReactionsBetween("FAVORITE",
                    from, to));
            long[] issuedAndConsumed = pointsQueryService.sumIssuedAndConsumedBetween(from, to);
            stat.setPointsIssued((int) issuedAndConsumed[0]);
            stat.setPointsConsumed((int) issuedAndConsumed[1]);
            stat.setAiMessageCount((int) aiSessionAppService.rangeMessageCount(from, to));

            // 阅读来自 M2 的 Redis 日计数（仅最近日期可靠，历史日为 0）
            DailyViews views = articleStatsService.drainDailyViews(statDate);
            stat.setViewCount(views.internal() + views.external());
            stat.setViewInternalCount(views.internal());
            stat.setViewExternalCount(views.external());

            dailyStatsMapper.upsert(stat);
            log.info("日统计已回填 {} 注册={} 发布={} 阅读={}", statDate, stat.getUserRegistered(),
                    stat.getArticlePublished(), stat.getViewCount());
        } finally {
            lock.release();
        }
    }

    private long aiSessionCountBetween(LocalDateTime from, LocalDateTime to) {
        // M6 提供今日口径；区间口径以近似值处理（今日之外返回 0 由后续迭代补端口）
        return to.isAfter(LocalDateTime.now(ZoneOffset.UTC)) ? aiSessionAppService.todayMessageCount()
                : 0;
    }
}
