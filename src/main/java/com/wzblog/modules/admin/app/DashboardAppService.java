package com.wzblog.modules.admin.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.util.Times;
import com.wzblog.modules.admin.domain.AuditLog;
import com.wzblog.modules.admin.domain.DailyStat;
import com.wzblog.modules.admin.infra.mapper.AuditQueryMapper;
import com.wzblog.modules.admin.infra.mapper.DailyStatsMapper;
import com.wzblog.modules.ai.app.AiSessionAppService;
import com.wzblog.modules.identity.app.UserQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板查询（M7 §5）：趋势读 daily_stats 预聚合，概览叠加实时指标。
 */
@Service
public class DashboardAppService {

    private final DailyStatsMapper dailyStatsMapper;
    private final AuditQueryMapper auditQueryMapper;
    private final UserQueryService userQueryService;
    private final AiSessionAppService aiSessionAppService;
    private final com.wzblog.modules.interaction.app.InteractionStatsService interactionStatsService;
    private final com.wzblog.modules.points.app.PointsQueryService pointsQueryService;

    public DashboardAppService(DailyStatsMapper dailyStatsMapper,
                               AuditQueryMapper auditQueryMapper,
                               UserQueryService userQueryService,
                               AiSessionAppService aiSessionAppService,
                               com.wzblog.modules.interaction.app.InteractionStatsService interactionStatsService,
                               com.wzblog.modules.points.app.PointsQueryService pointsQueryService) {
        this.dailyStatsMapper = dailyStatsMapper;
        this.auditQueryMapper = auditQueryMapper;
        this.userQueryService = userQueryService;
        this.aiSessionAppService = aiSessionAppService;
        this.interactionStatsService = interactionStatsService;
        this.pointsQueryService = pointsQueryService;
    }

    /**
     * 概览：当日指标实时查询（定时任务只回填昨日——聚合任务 03:10 跑，
     * 读"今日行"全天恒 0，曾在实测中暴露），历史趋势仍走 daily_stats。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.LocalDateTime from = today.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        java.time.LocalDateTime to = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", today.toString());
        result.put("totalUsers", userQueryService.countUsers());
        result.put("todayRegistered", userQueryService.countRegisteredBetween(from, to));
        result.put("todayComments",
                interactionStatsService.countCommentsCreatedBetween(from, to));
        result.put("todayAiMessages", aiSessionAppService.todayMessageCount());
        long[] points = pointsQueryService.sumIssuedAndConsumedBetween(from, to);
        result.put("todayPointsIssued", points[0]);
        result.put("todayPointsConsumed", points[1]);
        return result;
    }

    public List<Map<String, Object>> articleTrend(int days) {
        return trend(days, List.of("viewCount", "likeCount", "commentCreated", "articlePublished"));
    }

    public List<Map<String, Object>> userTrend(int days) {
        return trend(days, List.of("userRegistered"));
    }

    public Map<String, Object> trafficSource(int days) {
        long internal = 0;
        long external = 0;
        for (DailyStat row : range(days)) {
            internal += row.getViewInternalCount();
            external += row.getViewExternalCount();
        }
        long total = internal + external;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("internal", internal);
        result.put("external", external);
        result.put("internalRate", total == 0 ? 0 : (double) internal / total);
        result.put("externalRate", total == 0 ? 0 : (double) external / total);
        return result;
    }

    public Map<String, Object> pointsStats(int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        long issued = 0;
        long consumed = 0;
        for (DailyStat row : range(days)) {
            issued += row.getPointsIssued();
            consumed += row.getPointsConsumed();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", row.getStatDate().toString());
            day.put("issued", row.getPointsIssued());
            day.put("consumed", row.getPointsConsumed());
            trend.add(day);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalIssued", issued);
        result.put("totalConsumed", consumed);
        result.put("trend", trend);
        return result;
    }

    /** 文章排行：读 daily_stats 无明细，改读 articles 冗余计数的聚合视图由 M2 提供；
     *  此处以预聚合外的实时排行接口返回（简化：调用方走 M2 公开排行 /articles/top）。 */
    public Map<String, Object> articleRank(String type, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("hint", "使用 GET /api/v1/articles/top?type=" + type + "&limit=" + limit);
        return result;
    }

    public PageResult<Map<String, Object>> auditLogs(String action, String targetType,
                                                     Long operatorId, LocalDateTime from,
                                                     LocalDateTime to, int page, int pageSize) {
        long total = auditQueryMapper.countPage(action, targetType, operatorId, from, to);
        int offset = (page - 1) * pageSize;
        List<AuditLog> logs = auditQueryMapper.selectPage(action, targetType, operatorId, from,
                to, pageSize, offset);
        List<Map<String, Object>> list = logs.stream().map(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(entry.getId()));
            item.put("operatorId", entry.getOperatorId() == null ? null
                    : String.valueOf(entry.getOperatorId()));
            item.put("action", entry.getAction());
            item.put("targetType", entry.getTargetType());
            item.put("targetId", entry.getTargetId());
            item.put("before", entry.getBeforeJson());
            item.put("after", entry.getAfterJson());
            item.put("ip", entry.getIp());
            item.put("createdAt", Times.toInstant(entry.getCreatedAt()));
            return item;
        }).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    // ------------------------------------------------------------ 内部

    private List<DailyStat> range(int days) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        return dailyStatsMapper.selectRange(today.minusDays(Math.max(0, days - 1L)), today);
    }

    private List<Map<String, Object>> trend(int days, List<String> metrics) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyStat row : range(days)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", row.getStatDate().toString());
            for (String metric : metrics) {
                day.put(metric, switch (metric) {
                    case "viewCount" -> row.getViewCount();
                    case "likeCount" -> row.getLikeCount();
                    case "commentCreated" -> row.getCommentCreated();
                    case "articlePublished" -> row.getArticlePublished();
                    case "userRegistered" -> row.getUserRegistered();
                    default -> 0;
                });
            }
            result.add(day);
        }
        return result;
    }
}
