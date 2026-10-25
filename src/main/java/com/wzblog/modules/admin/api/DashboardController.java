package com.wzblog.modules.admin.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.admin.app.DashboardAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 看板路由（ADMIN，M7 §3）。
 */
@RestController
@RequestMapping("/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final DashboardAppService dashboardAppService;

    public DashboardController(DashboardAppService dashboardAppService) {
        this.dashboardAppService = dashboardAppService;
    }

    @GetMapping("/overview")
    public ResponseEntity<Result<Map<String, Object>>> overview() {
        return ResponseEntity.ok(Result.ok(dashboardAppService.overview()));
    }

    @GetMapping("/article-trend")
    public ResponseEntity<Result<List<Map<String, Object>>>> articleTrend(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.articleTrend(clamp(days))));
    }

    @GetMapping("/user-trend")
    public ResponseEntity<Result<List<Map<String, Object>>>> userTrend(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.userTrend(clamp(days))));
    }

    @GetMapping("/traffic-source")
    public ResponseEntity<Result<Map<String, Object>>> trafficSource(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.trafficSource(clamp(days))));
    }

    @GetMapping("/article-rank")
    public ResponseEntity<Result<Map<String, Object>>> articleRank(
            @RequestParam(value = "type", defaultValue = "view") String type,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.articleRank(type, limit)));
    }

    @GetMapping("/points-stats")
    public ResponseEntity<Result<Map<String, Object>>> pointsStats(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.pointsStats(clamp(days))));
    }

    private static int clamp(int days) {
        return Math.min(Math.max(days, 1), 92);
    }
}
