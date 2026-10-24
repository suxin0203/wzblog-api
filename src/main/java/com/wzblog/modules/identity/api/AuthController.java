package com.wzblog.modules.identity.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.security.AccessTokenDetails;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.ForgotRequest;
import com.wzblog.modules.identity.api.dto.ForgotResetRequest;
import com.wzblog.modules.identity.api.dto.ForgotVerifyRequest;
import com.wzblog.modules.identity.api.dto.LoginRequest;
import com.wzblog.modules.identity.api.dto.LogoutRequest;
import com.wzblog.modules.identity.api.dto.RefreshRequest;
import com.wzblog.modules.identity.api.dto.RegisterRequest;
import com.wzblog.modules.identity.api.vo.CaptchaVO;
import com.wzblog.modules.identity.api.vo.ForgotSentVO;
import com.wzblog.modules.identity.api.vo.ResetTokenVO;
import com.wzblog.modules.identity.api.vo.TokenPairVO;
import com.wzblog.modules.identity.api.vo.UserSelfVO;
import com.wzblog.modules.identity.app.AuthAppService;
import com.wzblog.modules.identity.app.CaptchaService;
import com.wzblog.modules.identity.app.PasswordResetAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证路由（R001–R009），Controller 不写 /api/v1 前缀。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CaptchaService captchaService;
    private final AuthAppService authAppService;
    private final PasswordResetAppService passwordResetAppService;

    public AuthController(CaptchaService captchaService,
                          AuthAppService authAppService,
                          PasswordResetAppService passwordResetAppService) {
        this.captchaService = captchaService;
        this.authAppService = authAppService;
        this.passwordResetAppService = passwordResetAppService;
    }

    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.ok(captchaService.generate());
    }

    @PostMapping("/register")
    public ResponseEntity<Result<UserSelfVO>> register(@Valid @RequestBody RegisterRequest request,
                                                       HttpServletRequest httpRequest) {
        UserSelfVO user = authAppService.register(request, ClientInfo.from(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(user));
    }

    @PostMapping("/login")
    public Result<TokenPairVO> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest httpRequest) {
        return Result.ok(authAppService.login(request, ClientInfo.from(httpRequest)));
    }

    @PostMapping("/refresh")
    public Result<TokenPairVO> refresh(@Valid @RequestBody RefreshRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.ok(authAppService.refresh(request.refreshToken(), ClientInfo.from(httpRequest)));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal CurrentUser currentUser,
                               @Valid @RequestBody LogoutRequest request,
                               Authentication authentication) {
        AccessTokenDetails details = accessDetails(authentication);
        authAppService.logout(currentUser, request.refreshToken(),
                details == null ? null : details.jti(),
                details == null ? null : details.expiresAt());
        return Result.ok();
    }

    @PostMapping("/logout-all")
    public Result<Void> logoutAll(@AuthenticationPrincipal CurrentUser currentUser,
                                  Authentication authentication) {
        AccessTokenDetails details = accessDetails(authentication);
        authAppService.logoutAll(currentUser,
                details == null ? null : details.jti(),
                details == null ? null : details.expiresAt());
        return Result.ok();
    }

    @PostMapping("/password/forgot/request")
    public Result<ForgotSentVO> forgotRequest(@Valid @RequestBody ForgotRequest request,
                                              HttpServletRequest httpRequest) {
        return Result.ok(passwordResetAppService.requestReset(request.email(), ClientInfo.from(httpRequest)));
    }

    @PostMapping("/password/forgot/verify")
    public Result<ResetTokenVO> forgotVerify(@Valid @RequestBody ForgotVerifyRequest request) {
        return Result.ok(passwordResetAppService.verifyCode(request.email(), request.code()));
    }

    @PostMapping("/password/forgot/reset")
    public Result<Void> forgotReset(@Valid @RequestBody ForgotResetRequest request) {
        passwordResetAppService.reset(request.resetToken(), request.newPassword());
        return Result.ok();
    }

    private AccessTokenDetails accessDetails(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof AccessTokenDetails details) {
            return details;
        }
        return null;
    }
}