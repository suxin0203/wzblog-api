package com.wzblog.modules.media.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.modules.media.api.dto.CleanupRequest;
import com.wzblog.modules.media.api.vo.AdminFileVO;
import com.wzblog.modules.media.api.vo.CleanupVO;
import com.wzblog.modules.media.app.FileAppService;
import com.wzblog.modules.media.app.FileCleanupService;
import com.wzblog.modules.media.domain.FileStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件后台路由（M5 §3，2 条）。
 */
@RestController
@RequestMapping("/admin/files")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFileController {

    private final FileAppService fileAppService;
    private final FileCleanupService cleanupService;

    public AdminFileController(FileAppService fileAppService, FileCleanupService cleanupService) {
        this.fileAppService = fileAppService;
        this.cleanupService = cleanupService;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<AdminFileVO>>> list(
            @RequestParam(value = "status", required = false) FileStatus status,
            @RequestParam(value = "uploader", required = false) Long uploader,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.min(Math.max(1, pageSize), 100);
        return ResponseEntity.ok(Result.ok(
                fileAppService.adminList(status, uploader, boundedPage, boundedSize)));
    }

    @PostMapping("/cleanup")
    public ResponseEntity<Result<CleanupVO>> cleanup(@RequestBody(required = false) CleanupRequest request) {
        boolean dryRun = request == null || request.dryRunOrDefault();
        return ResponseEntity.ok(Result.ok(cleanupService.run(dryRun)));
    }
}
