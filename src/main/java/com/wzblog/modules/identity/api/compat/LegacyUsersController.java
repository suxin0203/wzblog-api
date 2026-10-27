package com.wzblog.modules.identity.api.compat;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.common.web.InfrastructureRoute;
import com.wzblog.common.web.LegacyResponses;
import com.wzblog.modules.identity.app.LegacyArithmeticCaptcha;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.identity.app.LegacyAuthAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧协议 /users/* 兼容端点（已上线小程序，docs/13 v2 §2.1）：
 * 算术验证码 / 注册 / 登录 / 刷新（不轮换）/ me。
 * 壳：HTTP 200 + 数字 code；密码 Base64、验证码答案 MD5；401 由 Java 过滤器按 Bearer 输出。
 */
@RestController
@InfrastructureRoute
public class LegacyUsersController {

    private static final Logger log = LoggerFactory.getLogger(LegacyUsersController.class);

    private final LegacyAuthAppService legacy;

    public LegacyUsersController(LegacyAuthAppService legacy) {
        this.legacy = legacy;
    }

    @GetMapping("/users/captcha")
    public Map<String, Object> captcha() {
        LegacyArithmeticCaptcha.Captcha generated = legacy.captcha();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("captchaId", generated.captchaId());
        data.put("num1", generated.num1());
        data.put("num2", generated.num2());
        return LegacyResponses.ok("ok", data);
    }

    @PostMapping("/users/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        try {
            var result = legacy.register(body.get("username"), decodeBase64(body.get("password")),
                    body.get("email"), body.get("openid"), body.get("captchaId"),
                    body.get("captchaAnswer"), ClientInfo.from(request));
            bindOpenidQuietly(body.get("openid"), result.user().id());
            return LegacyResponses.okWithTokens("注册成功", result.tokens().accessToken(),
                    result.tokens().refreshToken(), legacy.toMap(result.user()));
        } catch (BusinessException ex) {
            return LegacyResponses.fail(ex.errorCode().httpStatus(), ex.getMessage());
        }
    }

    @PostMapping("/users/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body,
                                     HttpServletRequest request) {
        try {
            var result = legacy.login(body.get("username"), decodeBase64(body.get("password")),
                    body.get("openid"), body.get("captchaId"), body.get("captchaAnswer"),
                    ClientInfo.from(request));
            bindOpenidQuietly(body.get("openid"), result.user().id());
            return LegacyResponses.okWithTokens("登录成功", result.tokens().accessToken(),
                    result.tokens().refreshToken(), legacy.toMap(result.user()));
        } catch (BusinessException ex) {
            return LegacyResponses.fail(ex.errorCode().httpStatus(), ex.getMessage());
        }
    }

    /** 旧语义：只换 access，refreshToken 不轮换（小程序沿用旧值，轮换会触发复用检测踢线）。 */
    @PostMapping("/users/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, String> body) {
        try {
            var result = legacy.refresh(body.get("refreshToken"));
            return LegacyResponses.okWithTokens("ok", result.accessToken(), null,
                    legacy.toMap(result.user()));
        } catch (BusinessException ex) {
            return LegacyResponses.fail(401, "refreshToken 无效或已过期");
        }
    }

    @GetMapping("/users/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal CurrentUser user) {
        if (user == null) {
            // Java 过滤器正常情况下已输出 401 JSON；兜底同语义
            return ResponseEntity.status(401)
                    .body(LegacyResponses.fail(401, "请登录"));
        }
        return ResponseEntity.ok(LegacyResponses.ok("ok", legacy.me(user)));
    }

    /** 事务提交后的顺带绑定：失败仅告警不影响登录/注册（旧系统宽松语义）。 */
    private void bindOpenidQuietly(String openid, Long userId) {
        if (openid == null || openid.isBlank()) {
            return;
        }
        try {
            legacy.bindOpenid(userId, openid.trim());
        } catch (BusinessException ex) {
            log.warn("顺带绑定 openid 失败（不影响登录/注册）userId={} code={}", userId,
                    ex.errorCode().code());
        }
    }

    /** 小程序密码 Base64 编码传输（旧系统约定）。 */
    private static String decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value; // 非 Base64 按明文处理（旧系统容忍）
        }
    }
}
