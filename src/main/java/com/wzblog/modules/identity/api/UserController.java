package com.wzblog.modules.identity.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.security.AccessTokenDetails;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.ChangePasswordRequest;
import com.wzblog.modules.identity.api.dto.UpdateProfileRequest;
import com.wzblog.modules.identity.api.vo.PublicUserProfileVO;
import com.wzblog.modules.identity.api.vo.SessionVO;
import com.wzblog.modules.identity.api.vo.UserSelfVO;
import com.wzblog.modules.identity.app.UserAppService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户资料路由（R010–R015）。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserAppService userAppService;

    public UserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @GetMapping("/me")
    public Result<UserSelfVO> me(@AuthenticationPrincipal CurrentUser currentUser) {
        return Result.ok(userAppService.me(currentUser));
    }

    @PutMapping("/me")
    public Result<UserSelfVO> updateMe(@AuthenticationPrincipal CurrentUser currentUser,
                                       @Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userAppService.updateProfile(currentUser, request));
    }

    @PutMapping("/me/password")
    public Result<Void> changePassword(@AuthenticationPrincipal CurrentUser currentUser,
                                       @Valid @RequestBody ChangePasswordRequest request,
                                       Authentication authentication) {
        AccessTokenDetails details = authentication != null
                && authentication.getDetails() instanceof AccessTokenDetails d ? d : null;
        userAppService.changePassword(currentUser, request,
                details == null ? null : details.jti(),
                details == null ? null : details.expiresAt());
        return Result.ok();
    }

    @GetMapping("/me/sessions")
    public Result<List<SessionVO>> sessions(@AuthenticationPrincipal CurrentUser currentUser) {
        return Result.ok(userAppService.sessions(currentUser));
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    public Result<Void> deleteSession(@AuthenticationPrincipal CurrentUser currentUser,
                                      @PathVariable String sessionId) {
        userAppService.deleteSession(currentUser, sessionId);
        return Result.ok();
    }

    @GetMapping("/{id}/profile")
    public Result<PublicUserProfileVO> publicProfile(@PathVariable Long id) {
        return Result.ok(userAppService.publicProfile(id));
    }
}