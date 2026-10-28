package com.wzblog.modules.points.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.dto.TitleUpsertRequest;
import com.wzblog.modules.points.api.vo.AdminTitleVO;
import com.wzblog.modules.points.api.vo.TitleVO;
import com.wzblog.modules.points.app.TitleAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 称号后台路由（ADMIN）。
 */
@RestController
@RequestMapping("/admin/titles")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTitleController {

    private final TitleAppService titleAppService;

    public AdminTitleController(TitleAppService titleAppService) {
        this.titleAppService = titleAppService;
    }

    @GetMapping
    public ResponseEntity<Result<List<AdminTitleVO>>> list() {
        return ResponseEntity.ok(Result.ok(titleAppService.adminList()));
    }

    @PostMapping
    public ResponseEntity<Result<TitleVO>> create(@AuthenticationPrincipal CurrentUser admin,
                                                  @Valid @RequestBody TitleUpsertRequest request) {
        return ResponseEntity.ok(Result.ok(titleAppService.create(admin, request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<TitleVO>> update(@AuthenticationPrincipal CurrentUser admin,
                                                  @PathVariable("id") Long id,
                                                  @Valid @RequestBody TitleUpsertRequest request) {
        return ResponseEntity.ok(Result.ok(titleAppService.update(admin, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Object>> delete(@AuthenticationPrincipal CurrentUser admin,
                                                 @PathVariable("id") Long id) {
        titleAppService.delete(admin, id);
        return ResponseEntity.ok(Result.ok(null));
    }
}
