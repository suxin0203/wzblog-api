package com.wzblog.modules.wechat.api.compat;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.InfrastructureRoute;
import com.wzblog.common.web.LegacyResponses;
import com.wzblog.modules.identity.app.LegacyAuthAppService;
import com.wzblog.modules.identity.app.WechatIdentityFacade;
import com.wzblog.modules.wechat.app.QrSessionService;
import com.wzblog.modules.wechat.domain.QrSession;
import com.wzblog.modules.wechat.app.WechatCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧协议 /wechat/* + /qr-login/* + /miniapp/* 兼容端点（已上线小程序，docs/13 v2 §2.2）。
 * 严格复刻 Node 行为：轮询在 CONFIRMED 时一次性签发双 Token 并置 COMPLETED；
 * entry 未绑定下发 bindToken；过期统一 HTTP 200 + code:410。
 */
@RestController
@InfrastructureRoute
public class LegacyWechatCompatController {

    private final QrSessionService qrSessions;
    private final WechatCodeService codeService;
    private final LegacyAuthAppService legacyAuth;
    private final WechatIdentityFacade identityFacade;

    public LegacyWechatCompatController(QrSessionService qrSessions, WechatCodeService codeService,
                                        LegacyAuthAppService legacyAuth,
                                        WechatIdentityFacade identityFacade) {
        this.qrSessions = qrSessions;
        this.codeService = codeService;
        this.legacyAuth = legacyAuth;
        this.identityFacade = identityFacade;
    }

    // ------------------------------------------------------------ /wechat/*

    /** code 换 openid：成功响应无 code 字段（request.js"格式3"分支），失败 {code:0}。 */
    @GetMapping("/wechat/openid/{code}")
    public Map<String, Object> openid(@PathVariable("code") String code) {
        try {
            WechatCodeService.OpenidResult session = codeService.code2Session(code);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("openid", session.openid());
            if (session.unionid() != null) {
                data.put("unionid", session.unionid());
            }
            return LegacyResponses.raw("获取openid成功", data);
        } catch (BusinessException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 0);
            body.put("message", "微信登录失败");
            body.put("errcode", ex.errorCode().httpStatus());
            body.put("errmsg", ex.getMessage());
            return body;
        }
    }

    /** openid 查绑定并登录：已绑定发 Token（data 含 refresh_token）；未绑定 {code:0}。 */
    @GetMapping("/wechat/userinfo/{openid}")
    public Map<String, Object> userinfo(@PathVariable("openid") String openid,
                                        HttpServletRequest request) {
        var user = legacyAuth.userByOpenid(openid);
        if (user == null) {
            return LegacyResponses.fail(0, "用户不存在");
        }
        if (!"ACTIVE".equals(user.status())) {
            return LegacyResponses.fail(403, "账号已停用，请联系管理员");
        }
        WechatIdentityFacade.IssuedTokens tokens = identityFacade.issueTokens(user.id(),
                ClientInfo.from(request));
        return LegacyResponses.okWithTokens("登录成功", tokens.accessToken(), null,
                legacyAuth.toMapWithRefresh(user, tokens.refreshToken()));
    }

    // ------------------------------------------------------------ /qr-login/*（PC 端）

    @PostMapping("/qr-login/session")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body,
                                             HttpServletRequest request) {
        ClientInfo client = ClientInfo.from(request);
        var created = qrSessions.create(client.ip(), client.userAgent(),
                body == null ? null : body.get("env_version"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sceneId", created.sceneId());
        data.put("expiresAt", Times.toInstant(created.expiresAt()).toString());
        data.put("miniProgramCode", created.miniProgramCode());
        return LegacyResponses.ok("创建扫码登录会话成功", data);
    }

    /** 轮询：CONFIRMED 一次性返回 Token 并置 COMPLETED（Node 语义，PC 唯一取 Token 入口）。 */
    @GetMapping("/qr-login/session/{sceneId}")
    public Map<String, Object> poll(@PathVariable("sceneId") String sceneId,
                                    HttpServletRequest request) {
        try {
            QrSessionService.PollResult result = qrSessions.poll(sceneId, ClientInfo.from(request));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", result.status());
            if ("pending".equals(result.status())) {
                data.put("expiresAt", Times.toInstant(result.expiresAt()).toString());
                return LegacyResponses.ok("等待扫码或确认", data);
            }
            if ("confirmed".equals(result.status())) {
                data.put("token", result.tokens().accessToken());
                data.put("refreshToken", result.tokens().refreshToken());
                data.put("user", legacyAuth.userMapById(result.userId()));
                return LegacyResponses.ok("扫码登录已确认", data);
            }
            if ("completed".equals(result.status())) {
                return LegacyResponses.ok("会话已完成，请刷新二维码", data);
            }
            Map<String, Object> expired = new LinkedHashMap<>();
            expired.put("status", "expired");
            Map<String, Object> body = LegacyResponses.fail(410, "二维码已过期，请刷新");
            body.put("data", expired);
            return body;
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.WECHAT_QR_NOT_FOUND) {
                return LegacyResponses.fail(404, "会话不存在或已被清理");
            }
            return LegacyResponses.fail(500, "查询扫码会话失败");
        }
    }

    /** 小程序码 PNG（no-store，避免 WebView 缓存"刷新不出来"——Node 同款处理）。 */
    @GetMapping("/qr-login/session/{sceneId}/code.png")
    public Object codePng(@PathVariable("sceneId") String sceneId,
                          @RequestParam(value = "env_version", required = false) String envVersion,
                          HttpServletResponse response) {
        byte[] png = qrSessions.qrCodePng(sceneId, envVersion);
        response.setContentType("image/png");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        return png;
    }

    // ------------------------------------------------------------ /miniapp/*（小程序端）

    @PostMapping("/miniapp/qr-login/entry")
    public Map<String, Object> entry(@RequestBody Map<String, String> body,
                                     HttpServletRequest request) {
        try {
            ClientInfo client = ClientInfo.from(request);
            var result = qrSessions.entry(trim(body.get("sceneId")), trim(body.get("code")),
                    client.ip(), client.userAgent());
            if (result.bindToken() != null) {
                // 未绑定分支：bindToken 走 Redis 快路径校验（login_ok 分支无 bindToken）
                qrSessions.registerBindFastPath(result.bindToken(), trim(body.get("sceneId")),
                        java.time.Duration.ofMinutes(5));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", result.action());
            if (result.bindToken() != null) {
                data.put("bindToken", result.bindToken());
            }
            data.put("client", result.client());
            return LegacyResponses.ok("login_ok".equals(result.action()) ? "已确认登录" : "需要注册或绑定账号",
                    data);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.WECHAT_QR_EXPIRED) {
                Map<String, Object> errorBody = LegacyResponses.fail(410,
                        "二维码已过期，请刷新PC页面重新扫码");
                Map<String, Object> expiredData = new LinkedHashMap<>();
                expiredData.put("status", "expired");
                errorBody.put("data", expiredData);
                return errorBody;
            }
            if (ex.errorCode() == ErrorCode.WECHAT_QR_NOT_FOUND) {
                return LegacyResponses.fail(404, "会话不存在或已被清理");
            }
            return LegacyResponses.fail(400, "微信接口错误：" + ex.getMessage());
        }
    }

    /** 注册并确认（bindToken 一次性；小程序当前 UI 未调用，语义按 Node 复刻）。 */
    @PostMapping("/miniapp/qr-login/register-and-confirm")
    public Map<String, Object> registerAndConfirm(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        try {
            String sceneId = trim(body.get("sceneId"));
            QrSession session = qrSessions.consumeBindToken(sceneId, trim(body.get("bindToken")));
            var result = legacyAuth.registerWithoutCaptcha(trim(body.get("username")),
                    decodeBase64(body.get("password")), body.get("email"), session.getTempOpenid(),
                    ClientInfo.from(request));
            qrSessions.confirmSession(sceneId, result.user().id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "confirmed");
            return LegacyResponses.ok("注册并确认PC登录成功", data);
        } catch (BusinessException ex) {
            return LegacyResponses.fail(ex.errorCode().httpStatus(), ex.getMessage());
        }
    }

    /** 绑定并确认（账号密码校验 + openid 绑定到该账号）。 */
    @PostMapping("/miniapp/qr-login/bind-and-confirm")
    public Map<String, Object> bindAndConfirm(@RequestBody Map<String, String> body) {
        try {
            String sceneId = trim(body.get("sceneId"));
            QrSession session = qrSessions.consumeBindToken(sceneId, trim(body.get("bindToken")));
            var user = legacyAuth.verifyCredentials(trim(body.get("username")),
                    decodeBase64(body.get("password")));
            if (user == null) {
                return LegacyResponses.fail(400, "账号或密码错误");
            }
            legacyAuth.bindOpenid(user.id(), session.getTempOpenid());
            qrSessions.confirmSession(sceneId, user.id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "confirmed");
            return LegacyResponses.ok("绑定账号并确认PC登录成功", data);
        } catch (BusinessException ex) {
            return LegacyResponses.fail(ex.errorCode().httpStatus(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 内部

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value.trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
