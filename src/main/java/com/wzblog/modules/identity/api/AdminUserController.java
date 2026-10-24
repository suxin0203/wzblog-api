package com.wzblog.modules.identity.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.common.security.Role;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.AdminCreateUserRequest;
import com.wzblog.modules.identity.api.dto.AdminUpdateUserRequest;
import com.wzblog.modules.identity.api.vo.AdminUserVO;
import com.wzblog.modules.identity.api.vo.LoginAuditVO;
import com.wzblog.modules.identity.app.AdminUserAppService;
import com.wzblog.modules.identity.domain.UserStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 管理员用户路由（R016–R021），仅 ADMIN。
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserAppService adminUserAppService;

    public AdminUserController(AdminUserAppService adminUserAppService) {
        this.adminUserAppService = adminUserAppService;
    }

    @GetMapping
    public Result<PageResult<AdminUserVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(adminUserAppService.list(keyword, role, status, page, pageSize));
    }

    @PostMapping
    public ResponseEntity<Result<AdminUserVO>> create(@Valid @RequestBody AdminCreateUserRequest request) {
        AdminUserVO user = adminUserAppService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(user));
    }

    @GetMapping("/{id}")
    public Result<AdminUserVO> detail(@PathVariable Long id) {
        return Result.ok(adminUserAppService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<AdminUserVO> update(@AuthenticationPrincipal CurrentUser currentUser,
                                      @PathVariable Long id,
                                      @Valid @RequestBody AdminUpdateUserRequest request) {
        return Result.ok(adminUserAppService.update(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser currentUser,
                               @PathVariable Long id) {
        adminUserAppService.delete(currentUser, id);
        return Result.ok();
    }

    @GetMapping("/{id}/login-audits")
    public Result<PageResult<LoginAuditVO>> loginAudits(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(adminUserAppService.loginAudits(id, success, from, to, page, pageSize));
    }
}