package com.wzblog.modules.admin.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.modules.admin.app.DashboardAppService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计日志查询（ADMIN，只读）。
 */
@RestController
@RequestMapping("/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final DashboardAppService dashboardAppService;

    public AuditLogController(DashboardAppService dashboardAppService) {
        this.dashboardAppService = dashboardAppService;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<Map<String, Object>>>> list(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "operatorId", required = false) Long operatorId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(dashboardAppService.auditLogs(action, targetType,
                operatorId, from, to, Math.max(1, page),
                Math.min(Math.max(1, pageSize), 100))));
    }
}
