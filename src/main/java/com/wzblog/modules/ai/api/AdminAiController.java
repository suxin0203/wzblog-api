package com.wzblog.modules.ai.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.modules.ai.api.vo.AiAdminSessionVO;
import com.wzblog.modules.ai.api.vo.AiAdminStatsVO;
import com.wzblog.modules.ai.api.vo.AiSessionVO;
import com.wzblog.modules.ai.app.AiSessionAppService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * AI 后台路由（ADMIN）。
 */
@RestController
@RequestMapping("/admin/ai")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiController {

    private final AiSessionAppService sessionAppService;

    public AdminAiController(AiSessionAppService sessionAppService) {
        this.sessionAppService = sessionAppService;
    }

    @GetMapping("/sessions")
    public ResponseEntity<Result<PageResult<AiAdminSessionVO>>> sessions(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(sessionAppService.adminSessions(userId, channel,
                from, to, Math.max(1, page), Math.min(Math.max(1, pageSize), 100))));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Result<AiSessionVO>> detail(@PathVariable("sessionId") String sessionId) {
        return ResponseEntity.ok(Result.ok(sessionAppService.adminDetail(sessionId)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Result<Object>> delete(@PathVariable("sessionId") String sessionId) {
        sessionAppService.adminDelete(sessionId);
        return ResponseEntity.ok(Result.ok(null));
    }

    @GetMapping("/stats")
    public ResponseEntity<Result<AiAdminStatsVO>> stats(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(Result.ok(sessionAppService.adminStats(from, to)));
    }
}
