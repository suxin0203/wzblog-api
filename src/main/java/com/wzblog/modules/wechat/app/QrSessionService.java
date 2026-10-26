package com.wzblog.modules.wechat.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.modules.identity.app.WechatIdentityFacade;
import com.wzblog.modules.wechat.domain.QrSession;
import com.wzblog.modules.wechat.infra.config.WechatProperties;
import com.wzblog.modules.wechat.infra.mapper.QrSessionMapper;
import com.wzblog.modules.wechat.infra.wechat.WeChatApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 扫码会话状态机（docs/13 v2，兼容已上线小程序语义）：
 * PENDING →（entry：已绑定直发 CONFIRMED / 未绑定发 bindToken）→ CONFIRMED →（轮询取票一次性）COMPLETED / EXPIRED。
 * 热点态（status）缓存 Redis 与会话同 TTL；DB 为事实源与审计。轮询接口在 CONFIRMED 时
 * 一次性签发双 Token 并置 COMPLETED——旧系统语义，PC 端唯一取 Token 入口。
 */
@Service
public class QrSessionService {

    private static final Logger log = LoggerFactory.getLogger(QrSessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SCENE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String STATUS_PREFIX = "wzblog:wechat:qr:";
    private static final String QR_PNG_PREFIX = "wzblog:wechat:qrcode:";

    private final QrSessionMapper sessionMapper;
    private final WeChatApiClient weChatApi;
    private final WechatIdentityFacade identityFacade;
    private final WechatProperties properties;
    private final StringRedisTemplate redis;

    public QrSessionService(QrSessionMapper sessionMapper, WeChatApiClient weChatApi,
                            WechatIdentityFacade identityFacade,
                            WechatProperties properties, StringRedisTemplate redis) {
        this.sessionMapper = sessionMapper;
        this.weChatApi = weChatApi;
        this.identityFacade = identityFacade;
        this.properties = properties;
        this.redis = redis;
    }

    // ------------------------------------------------------------ 创建与轮询

    /** 创建会话；PNG 以 base64 dataURL 返回（旧契约 createSession 响应字段 miniProgramCode）。 */
    @Transactional
    public CreatedSession create(String clientIp, String userAgent, String envVersion) {
        LocalDateTime now = Times.nowUtc();
        LocalDateTime expiresAt = now.plusMinutes(properties.getSessionTtlMinutes());
        String sceneId = uniqueSceneId();
        QrSession session = new QrSession();
        session.setSceneId(sceneId);
        session.setStatus(QrSession.PENDING);
        session.setClientIp(clientIp);
        session.setUserAgent(truncate(userAgent, 255));
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        sessionMapper.insert(session);

        byte[] png = qrCodePng(sceneId, envVersion);
        String dataUrl = "data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(png);
        cacheStatus(sceneId, QrSession.PENDING, expiresAt);
        return new CreatedSession(sceneId, expiresAt, dataUrl);
    }

    public record CreatedSession(String sceneId, LocalDateTime expiresAt, String miniProgramCode) {
    }

    /** 轮询：PENDING 只读；CONFIRMED 一次性签发双 Token 并置 COMPLETED；过期懒置 EXPIRED。 */
    @Transactional
    public PollResult poll(String sceneId, com.wzblog.common.web.ClientInfo client) {
        QrSession session = requireSession(sceneId);
        LocalDateTime now = Times.nowUtc();
        if (session.getExpiresAt().isBefore(now) || QrSession.EXPIRED.equals(session.getStatus())) {
            sessionMapper.expireIfDue(sceneId, now);
            evictStatus(sceneId);
            return PollResult.expired();
        }
        switch (session.getStatus()) {
            case QrSession.PENDING -> {
                return PollResult.pending(session.getExpiresAt());
            }
            case QrSession.CONFIRMED -> {
                Long userId = session.getUserId();
                if (userId == null) {
                    sessionMapper.expireIfDue(sceneId, now);
                    return PollResult.expired();
                }
                // 兑换完成只允许一次：条件更新抢到者签发 Token
                if (sessionMapper.complete(sceneId, now) == 0) {
                    return PollResult.completed();
                }
                WechatIdentityFacade.IssuedTokens tokens = identityFacade.issueTokens(userId, client);
                evictStatus(sceneId);
                return PollResult.confirmed(tokens, userId);
            }
            default -> {
                return PollResult.completed();
            }
        }
    }

    public record PollResult(String status, LocalDateTime expiresAt,
                             WechatIdentityFacade.IssuedTokens tokens, Long userId) {

        static PollResult pending(LocalDateTime expiresAt) {
            return new PollResult("pending", expiresAt, null, null);
        }

        static PollResult expired() {
            return new PollResult("expired", null, null, null);
        }

        static PollResult completed() {
            return new PollResult("completed", null, null, null);
        }

        static PollResult confirmed(WechatIdentityFacade.IssuedTokens tokens, Long userId) {
            return new PollResult("confirmed", null, tokens, userId);
        }
    }

    // ------------------------------------------------------------ 小程序入口

    /**
     * 扫码进入（旧契约 /miniapp/qr-login/entry）：
     * code→openid；已绑定 → 确认会话返回 login_ok；未绑定 → 存 temp_openid + bindToken。
     */
    @Transactional
    public EntryResult entry(String sceneId, String code, String clientIp, String userAgent) {
        QrSession session = requireSession(sceneId);
        LocalDateTime now = Times.nowUtc();
        if (session.getExpiresAt().isBefore(now) || !QrSession.PENDING.equals(session.getStatus())) {
            sessionMapper.expireIfDue(sceneId, now);
            throw new BusinessException(ErrorCode.WECHAT_QR_EXPIRED);
        }
        WeChatApiClient.SessionResult wx = weChatApi.code2Session(code);
        String openid = wx.openid();

        var identity = identityFacade.loginOrCreate(openid, wx.unionid());
        if (identity.active() && !identity.created()) {
            // 已是老用户：直接确认（并发确认由条件更新保证单胜）
            if (sessionMapper.confirm(sceneId, identity.userId(), now) == 0) {
                throw new BusinessException(ErrorCode.WECHAT_QR_ALREADY_CONFIRMED);
            }
            cacheStatus(sceneId, QrSession.CONFIRMED, session.getExpiresAt());
            return new EntryResult("login_ok", null, describeClient(session), identity.userId());
        }
        // 新 openid（identity.created=true，自动账号已建）或停用：下发 bindToken 走注册/绑定
        String bindToken = randomToken();
        LocalDateTime bindExpires = now.plusMinutes(properties.getBindTtlMinutes());
        int saved = sessionMapper.saveEntryState(sceneId, openid, sha256Hex(bindToken),
                bindExpires, now);
        if (saved == 0) {
            throw new BusinessException(ErrorCode.WECHAT_QR_ALREADY_CONFIRMED);
        }
        return new EntryResult("need_register_or_bind", bindToken, describeClient(session),
                identity.userId());
    }

    public record EntryResult(String action, String bindToken, Map<String, Object> client,
                              Long userId) {
    }

    /** bindToken 校验并消费（Redis DEL 原子 + DB 哈希置空，二者任一失败即整体失败）。 */
    @Transactional
    public QrSession consumeBindToken(String sceneId, String bindToken) {
        QrSession session = requireSession(sceneId);
        String hash = sha256Hex(bindToken);
        String fastKey = "wzblog:wechat:qr:bind:" + hash;
        String boundScene = redis.opsForValue().get(fastKey);
        boolean fastOk = boundScene != null && boundScene.equals(sceneId);
        boolean dbOk = session.getBindTokenHash() != null && session.getBindTokenHash().equals(hash)
                && session.getBindExpiresAt() != null
                && session.getBindExpiresAt().isAfter(Times.nowUtc());
        if (!fastOk || !dbOk) {
            throw new BusinessException(ErrorCode.WECHAT_BIND_TOKEN_INVALID);
        }
        int cleared = sessionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QrSession>()
                        .eq(QrSession::getSceneId, sceneId)
                        .eq(QrSession::getBindTokenHash, hash)
                        .set(QrSession::getBindTokenHash, null)
                        .set(QrSession::getBindExpiresAt, null)
                        .set(QrSession::getUpdatedAt, Times.nowUtc()));
        redis.delete(fastKey);
        if (cleared == 0) {
            throw new BusinessException(ErrorCode.WECHAT_BIND_TOKEN_INVALID);
        }
        return session;
    }

    /** 供 bindToken 流程复用：登记 bindToken 到会话（Redis 快路径）。 */
    public void registerBindFastPath(String bindToken, String sceneId, Duration ttl) {
        String hash = sha256Hex(bindToken);
        redis.opsForValue().set("wzblog:wechat:qr:bind:" + hash, sceneId, ttl);
    }

    /** 确认会话（注册/绑定成功后调用）。 */
    @Transactional
    public void confirmSession(String sceneId, Long userId) {
        if (sessionMapper.confirm(sceneId, userId, Times.nowUtc()) == 0) {
            QrSession session = sessionMapper.selectBySceneId(sceneId);
            if (session != null && QrSession.CONFIRMED.equals(session.getStatus())) {
                return; // 已确认（幂等）
            }
            throw new BusinessException(ErrorCode.WECHAT_QR_EXPIRED);
        }
    }

    // ------------------------------------------------------------ 码图与辅助

    /** 码 PNG：Redis 缓存（与会话同 TTL，含版本号入键），未命中调上游。 */
    public byte[] qrCodePng(String sceneId, String envVersion) {
        String version = envVersion == null || envVersion.isBlank()
                ? properties.getEnvVersion() : envVersion;
        String key = QR_PNG_PREFIX + sceneId + ":" + version;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return java.util.Base64.getDecoder().decode(cached);
        }
        byte[] png = weChatApi.getQrCodePng(sceneId, version);
        redis.opsForValue().set(key, java.util.Base64.getEncoder().encodeToString(png),
                Duration.ofMinutes(properties.getSessionTtlMinutes()));
        return png;
    }

    public QrSession requireSession(String sceneId) {
        if (sceneId == null || sceneId.isBlank() || sceneId.length() > 32) {
            throw new BusinessException(ErrorCode.WECHAT_QR_NOT_FOUND);
        }
        QrSession session = sessionMapper.selectBySceneId(sceneId.trim());
        if (session == null) {
            throw new BusinessException(ErrorCode.WECHAT_QR_NOT_FOUND);
        }
        return session;
    }

    /** 旧契约 entry 响应的 client 描述（UA/IP 脱敏，复刻 Node describeClient）。 */
    public Map<String, Object> describeClient(QrSession session) {
        String ua = session.getUserAgent() == null ? "" : session.getUserAgent();
        boolean inWeChat = ua.toLowerCase(Locale.ROOT).contains("micromessenger");
        boolean isMobile = ua.toLowerCase(Locale.ROOT).matches(".*(mobile|android|iphone).*");
        String os = ua.contains("Windows") ? "Windows 电脑"
                : ua.contains("Mac") ? "Mac 电脑"
                : ua.contains("Android") ? "Android 手机"
                : ua.contains("iPhone") ? "iOS 设备" : "未知设备";
        Map<String, Object> client = new java.util.LinkedHashMap<>();
        client.put("channel", "pc");
        client.put("channel_label", "电脑网页");
        client.put("device", os + " · 浏览器");
        client.put("os", os);
        client.put("browser", inWeChat ? "微信内置浏览器" : "浏览器");
        client.put("is_mobile", isMobile);
        client.put("is_wechat", inWeChat);
        client.put("ip", maskIp(session.getClientIp()));
        return client;
    }

    /** 登录/注册带 openid 成功后的可选自动确认（默认关闭，严格复刻旧系统二次扫码）。 */
    @Transactional
    public void autoConfirmPendingForOpenid(String openid, Long userId) {
        if (!properties.isAutoConfirmOnBind()) {
            return;
        }
        // 简化实现：仅确认 temp_openid 匹配且 PENDING 的最新会话
        var pending = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QrSession>()
                        .eq(QrSession::getTempOpenid, openid)
                        .eq(QrSession::getStatus, QrSession.PENDING)
                        .gt(QrSession::getExpiresAt, Times.nowUtc())
                        .orderByDesc(QrSession::getId)
                        .last("LIMIT 1"));
        pending.forEach(session -> confirmSession(session.getSceneId(), userId));
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireSweep() {
        int expired = sessionMapper.expireBatch(Times.nowUtc());
        if (expired > 0) {
            log.info("扫码会话清理：{} 条置 EXPIRED", expired);
        }
    }

    // ------------------------------------------------------------ 内部

    private void cacheStatus(String sceneId, String status, LocalDateTime expiresAt) {
        redis.opsForValue().set(STATUS_PREFIX + sceneId, status,
                Duration.between(Times.nowUtc(), expiresAt));
    }

    private void evictStatus(String sceneId) {
        redis.delete(STATUS_PREFIX + sceneId);
    }

    private String uniqueSceneId() {
        for (int attempt = 0; attempt < 3; attempt++) {
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 16; i++) {
                builder.append(SCENE_ALPHABET.charAt(RANDOM.nextInt(SCENE_ALPHABET.length())));
            }
            String candidate = builder.toString();
            if (sessionMapper.selectBySceneId(candidate) == null) {
                return candidate;
            }
        }
        throw new IllegalStateException("sceneId 生成冲突");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        String value = ip.split(",")[0].trim();
        if (value.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            String[] parts = value.split("\\.");
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return value.length() > 12 ? value.substring(0, 12) + "…" : value;
    }

    private static String truncate(String value, int max) {
        return value == null ? null : (value.length() <= max ? value : value.substring(0, max));
    }
}
