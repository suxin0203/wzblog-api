package com.wzblog.modules.wechat.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.app.WechatIdentityFacade;
import com.wzblog.modules.wechat.app.QrSessionService;
import com.wzblog.modules.wechat.app.WechatLoginService;
import com.wzblog.modules.wechat.api.dto.QrConfirmRequest;
import com.wzblog.modules.wechat.api.dto.QrRegisterConfirmRequest;
import com.wzblog.modules.wechat.api.dto.QrTicketExchangeRequest;
import com.wzblog.modules.wechat.api.dto.WechatBindRequest;
import com.wzblog.modules.wechat.api.dto.WechatLoginRequest;
import com.wzblog.modules.wechat.api.vo.QrConfirmVO;
import com.wzblog.modules.wechat.api.vo.QrSessionVO;
import com.wzblog.modules.wechat.api.vo.QrStatusVO;
import com.wzblog.modules.wechat.api.vo.WechatBindVO;
import com.wzblog.modules.wechat.api.vo.WechatLoginVO;
import com.wzblog.modules.wechat.api.vo.WechatUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信登录与扫码（新契约 9 条，M8 设计文档 §3）：Controller 不写 /api/v1 前缀。
 * - 轮询禁止签发 Token：PC 唯一取 Token 入口是 /auth/qr/tickets/exchange（M8 §8）。
 * - /code 返回 PNG 二进制，是统一响应壳的显式例外；失败仍走 JSON 错误体。
 */
@RestController
@RequestMapping("/auth")
public class QrLoginController {

    private final WechatLoginService wechatLogin;
    private final QrSessionService qrSessions;

    public QrLoginController(WechatLoginService wechatLogin, QrSessionService qrSessions) {
        this.wechatLogin = wechatLogin;
        this.qrSessions = qrSessions;
    }

    // ------------------------------------------------------------ 小程序 code 登录 / 绑定

    /** code 换 openid，登录或自动创建 USER，返回双 Token + 用户简版。 */
    @PostMapping("/wechat/login")
    public Result<WechatLoginVO> wechatLogin(@Valid @RequestBody WechatLoginRequest request,
                                             HttpServletRequest httpRequest) {
        var result = wechatLogin.loginByCode(request.code().trim(),
                trimToNull(request.nickname()), trimToNull(request.avatarKey()),
                ClientInfo.from(httpRequest));
        return Result.ok(WechatLoginVO.bearer(result.tokens().accessToken(),
                result.tokens().expiresIn(), result.tokens().refreshToken(),
                result.tokens().refreshExpiresIn(), toUserVO(result.user())));
    }

    /** 已登录网页用户绑定 openid（USER：绑定目标恒为当前登录用户）。 */
    @PostMapping("/wechat/bind")
    public Result<WechatBindVO> wechatBind(@AuthenticationPrincipal CurrentUser currentUser,
                                           @Valid @RequestBody WechatBindRequest request) {
        requireLogin(currentUser);
        var view = wechatLogin.bindByCode(request.code().trim(), currentUser.id());
        return Result.ok(new WechatBindVO(true, toUserVO(view)));
    }

    // ------------------------------------------------------------ PC 扫码会话

    /** 创建扫码会话（无请求体字段；TTL 120s）。 */
    @PostMapping("/qr/sessions")
    public Result<QrSessionVO> createSession(HttpServletRequest httpRequest) {
        ClientInfo client = ClientInfo.from(httpRequest);
        var created = qrSessions.openQrSession(client.ip(), client.userAgent());
        return Result.ok(new QrSessionVO(created.sceneId(), "PENDING", created.expiresAt()));
    }

    /** 轮询状态；CONFIRMED 附 ticket（不消费）。禁止在本接口签发 Token。 */
    @GetMapping("/qr/sessions/{sceneId}")
    public Result<QrStatusVO> poll(@PathVariable("sceneId") String sceneId,
                                   HttpServletRequest httpRequest) {
        var status = qrSessions.pollQrStatus(sceneId, ClientInfo.from(httpRequest).ip());
        return Result.ok(new QrStatusVO(status.sceneId(), status.status(),
                status.ticket(), status.expiresAt()));
    }

    /** 小程序码 PNG 二进制（统一响应壳显式例外；no-store 防缓存陈旧码）。 */
    @GetMapping("/qr/sessions/{sceneId}/code")
    public ResponseEntity<byte[]> code(@PathVariable("sceneId") String sceneId,
                                       @RequestParam(value = "env_version", required = false) String envVersion) {
        qrSessions.requireSession(sceneId); // 404 JSON 错误体
        byte[] png = qrSessions.qrCodePng(sceneId, normalizeEnvVersion(envVersion));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    // ------------------------------------------------------------ 小程序确认三步

    /** 确认（小程序 JWT 或 bindToken 二选一）；成功下发一次性 bindToken（60s）。 */
    @PostMapping("/qr/sessions/{sceneId}/confirm")
    public Result<QrConfirmVO> confirm(@PathVariable("sceneId") String sceneId,
                                       @AuthenticationPrincipal CurrentUser currentUser,
                                       @Valid @RequestBody(required = false) QrConfirmRequest request) {
        String bindToken = request == null ? null : request.bindToken();
        var result = qrSessions.confirmQrSession(sceneId, bindToken,
                currentUser == null ? null : currentUser.id());
        return Result.ok(toConfirmVO(result));
    }

    /** 注册并确认：bindToken 建正式账号（source=QR）后确认。 */
    @PostMapping("/qr/sessions/{sceneId}/register-and-confirm")
    public Result<QrConfirmVO> registerAndConfirm(@PathVariable("sceneId") String sceneId,
                                                  @Valid @RequestBody QrRegisterConfirmRequest request) {
        var result = qrSessions.registerAndConfirm(sceneId, request.bindToken(),
                request.username(), request.password(), trimToNull(request.nickname()));
        return Result.ok(toConfirmVO(result));
    }

    /** 绑定并确认：登录态 + bindToken，绑定到当前登录用户。 */
    @PostMapping("/qr/sessions/{sceneId}/bind-and-confirm")
    public Result<QrConfirmVO> bindAndConfirm(@PathVariable("sceneId") String sceneId,
                                              @AuthenticationPrincipal CurrentUser currentUser,
                                              @Valid @RequestBody(required = false) QrConfirmRequest request) {
        requireLogin(currentUser);
        String bindToken = request == null ? null : request.bindToken();
        var result = qrSessions.bindAndConfirm(sceneId, bindToken, currentUser.id());
        return Result.ok(toConfirmVO(result));
    }

    // ------------------------------------------------------------ PC 换票

    /** 一次性 ticket 换双 Token（PC 端唯一取 Token 入口）。 */
    @PostMapping("/qr/tickets/exchange")
    public Result<WechatLoginVO> exchange(@Valid @RequestBody QrTicketExchangeRequest request,
                                          HttpServletRequest httpRequest) {
        var exchange = qrSessions.exchangeTicket(request.ticket(), ClientInfo.from(httpRequest));
        return Result.ok(WechatLoginVO.bearer(exchange.tokens().accessToken(),
                exchange.tokens().expiresIn(), exchange.tokens().refreshToken(),
                exchange.tokens().refreshExpiresIn(), toUserVO(exchange.user())));
    }

    // ------------------------------------------------------------ 内部

    private static void requireLogin(CurrentUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }

    private static WechatUserVO toUserVO(WechatIdentityFacade.WechatUserView view) {
        return WechatUserVO.from(view.id(), view.username(), view.nickname(), view.avatarKey(),
                view.role(), view.status(), view.openidBound());
    }

    private static QrConfirmVO toConfirmVO(QrSessionService.QrConfirm result) {
        return new QrConfirmVO(result.sceneId(), result.status(), result.bindToken(),
                result.bindTokenExpiresAt());
    }

    /** env_version 白名单（M8 v2 §0-3）：release/trial/develop 之外的取值回落默认配置。 */
    private static String normalizeEnvVersion(String envVersion) {
        if (envVersion == null) {
            return null;
        }
        return switch (envVersion) {
            case "release", "trial", "develop" -> envVersion;
            default -> null;
        };
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
