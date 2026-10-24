package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.LoginRequest;
import com.wzblog.modules.identity.api.dto.RegisterRequest;
import com.wzblog.modules.identity.api.vo.TokenPairVO;
import com.wzblog.modules.identity.api.vo.UserSelfVO;
import com.wzblog.modules.identity.domain.LoginFailureReason;
import com.wzblog.modules.identity.domain.RefreshToken;
import com.wzblog.modules.identity.domain.RevokeReason;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.UserSource;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.modules.identity.domain.event.UserLoggedInEvent;
import com.wzblog.modules.identity.infra.config.AuthProperties;
import com.wzblog.modules.identity.infra.mapper.RefreshTokenMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.redis.LoginAttemptStore;
import com.wzblog.modules.identity.infra.redis.TokenStateStore;
import com.wzblog.modules.identity.infra.security.IdentityCrypto;
import com.wzblog.modules.identity.infra.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证用例：注册、登录、刷新轮换、登出、令牌签发与吊销。
 */
@Service
public class AuthAppService {

    private static final Logger log = LoggerFactory.getLogger(AuthAppService.class);
    private static final int REFRESH_GRACE_POLLS = 20;
    private static final long REFRESH_GRACE_POLL_MILLIS = 100L;

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final LoginAttemptStore attemptStore;
    private final TokenStateStore tokenStateStore;
    private final JwtService jwtService;
    private final LoginAuditWriter auditWriter;
    private final AuthProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final UserVoAssembler assembler;
    private final String dummyPasswordHash;

    public AuthAppService(UserMapper userMapper,
                          RefreshTokenMapper refreshTokenMapper,
                          PasswordEncoder passwordEncoder,
                          CaptchaService captchaService,
                          LoginAttemptStore attemptStore,
                          TokenStateStore tokenStateStore,
                          JwtService jwtService,
                          LoginAuditWriter auditWriter,
                          AuthProperties properties,
                          ApplicationEventPublisher eventPublisher,
                          UserVoAssembler assembler) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.attemptStore = attemptStore;
        this.tokenStateStore = tokenStateStore;
        this.jwtService = jwtService;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.assembler = assembler;
        this.dummyPasswordHash = passwordEncoder.encode("wzblog-dummy-password-1");
    }

    // ------------------------------------------------------------------ 注册

    @Transactional
    public UserSelfVO register(RegisterRequest request, ClientInfo client) {
        if (!attemptStore.tryAcquireRegisterIp(client.ip())) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "注册过于频繁，请稍后再试", 3600L);
        }
        captchaService.verify(request.captchaId(), request.captchaCode());

        String username = request.username().trim();
        if (username.toLowerCase(Locale.ROOT).startsWith("deleted_")) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "用户名不合法", null);
        }
        String email = normalizeEmail(request.email());
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new BusinessException(ErrorCode.USER_USERNAME_TAKEN);
        }
        if (email != null && userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            throw new BusinessException(ErrorCode.USER_EMAIL_TAKEN);
        }

        LocalDateTime now = Times.nowUtc();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setNickname(defaultNickname(request.nickname(), username));
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setSource(UserSource.WEB);
        user.setVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw translateDuplicate(username, email);
        }
        return assembler.toSelf(userMapper.selectById(user.getId()));
    }

    // ------------------------------------------------------------------ 登录

    @Transactional
    public TokenPairVO login(LoginRequest request, ClientInfo client) {
        String username = request.username().trim();
        String usernameHash = IdentityCrypto.sha256Hex(username.toLowerCase(Locale.ROOT));

        if (!attemptStore.tryAcquireLoginIp(client.ip(), attemptStore.loginIpLimitPerMinute())) {
            auditFailure(null, username, LoginFailureReason.RATE_LIMITED, client);
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "登录过于频繁，请稍后再试", 60L);
        }

        try {
            captchaService.verify(request.captchaId(), request.captchaCode());
        } catch (BusinessException ex) {
            auditFailure(null, username, LoginFailureReason.CAPTCHA_INVALID, client);
            throw ex;
        }

        Long lockRemaining = attemptStore.lockRemainingSeconds(usernameHash);
        if (lockRemaining != null) {
            auditFailure(null, username, LoginFailureReason.LOCKED, client);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                    ErrorCode.AUTH_ACCOUNT_LOCKED.defaultMessage(), lockRemaining);
        }

        User user = findByUsername(username);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            auditFailure(null, username, LoginFailureReason.BAD_CREDENTIALS, client);
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            long failures = attemptStore.incrementFailure(usernameHash);
            if (failures >= properties.getLoginMaxFailures()) {
                attemptStore.lock(usernameHash);
                auditFailure(user.getId(), username, LoginFailureReason.LOCKED, client);
                throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                        ErrorCode.AUTH_ACCOUNT_LOCKED.defaultMessage(),
                        properties.getLoginLockTtl().toSeconds());
            }
            auditFailure(user.getId(), username, LoginFailureReason.BAD_CREDENTIALS, client);
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            auditFailure(user.getId(), username, LoginFailureReason.DISABLED, client);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        attemptStore.clearFailures(usernameHash);

        LocalDateTime nowUtc = Times.nowUtc();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLastLoginAt, nowUtc)
                .set(User::getLastLoginIp, client.ip())
                .set(User::getUpdatedAt, nowUtc));

        IssuedRefresh refresh = createRefreshToken(user.getId(), UUID.randomUUID().toString(), null, client, nowUtc);
        auditWriter.recordSuccess(user.getId(), username, client);

        JwtService.IssuedAccessToken access = jwtService.issue(user.getId(), user.getRole());
        boolean firstLoginOfDay = attemptStore.markFirstLoginOfDay(user.getId());
        if (firstLoginOfDay) {
            eventPublisher.publishEvent(new UserLoggedInEvent(
                    user.getId(), Instant.now(), client.ip(), true));
        }
        return toPair(access, refresh.plainToken());
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 刷新轮换。吊销 family 后需要抛出业务异常，故 noRollbackFor 让已写入的吊销生效（M1 §6.5）。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenPairVO refresh(String rawRefreshToken, ClientInfo client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        String tokenHash = IdentityCrypto.sha256Hex(rawRefreshToken);
        RefreshToken row = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getTokenHash, tokenHash));
        if (row == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        LocalDateTime now = Times.nowUtc();
        if (row.isRevoked()) {
            return handleRevokedToken(row, tokenHash, client, now);
        }
        if (!row.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }

        userMapper.lockById(row.getUserId());
        User user = userMapper.selectById(row.getUserId());
        if (user == null || user.isDeleted()) {
            revokeFamily(row.getFamilyId(), row.getUserId(), RevokeReason.ADMIN);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            revokeFamily(row.getFamilyId(), row.getUserId(), RevokeReason.ADMIN);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, row.getId())
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, now)
                .set(RefreshToken::getRevokeReason, RevokeReason.ROTATED));
        if (updated == 0) {
            RefreshToken latest = refreshTokenMapper.selectById(row.getId());
            return handleRevokedToken(latest, tokenHash, client, Times.nowUtc());
        }

        IssuedRefresh issued = createRefreshToken(user.getId(), row.getFamilyId(), row.getId(), client, now);
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, row.getId())
                .set(RefreshToken::getReplacedById, issued.entity().getId()));

        JwtService.IssuedAccessToken access = jwtService.issue(user.getId(), user.getRole());
        tokenStateStore.saveGrace(tokenHash, new TokenStateStore.GracePayload(
                access.value(), access.expiresIn(),
                issued.plainToken(), jwtService.refreshTtlSeconds(),
                uaHash(client.userAgent()), row.getFamilyId(),
                properties.getRefreshGrace().toSeconds()));
        return toPair(access, issued.plainToken());
    }

    private TokenPairVO handleRevokedToken(RefreshToken row, String tokenHash, ClientInfo client,
                                           LocalDateTime now) {
        if (row == null || row.getRevokedAt() == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        if (row.getRevokeReason() != RevokeReason.ROTATED || row.getReplacedById() == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        boolean sameUserAgent = Objects.equals(uaHash(client.userAgent()), uaHash(row.getUserAgent()));
        boolean withinGrace = row.getRevokedAt().plus(properties.getRefreshGrace()).isAfter(now);
        if (sameUserAgent && withinGrace) {
            for (int i = 0; i < REFRESH_GRACE_POLLS; i++) {
                Optional<TokenStateStore.GracePayload> grace = tokenStateStore.getGrace(tokenHash);
                if (grace.isPresent()) {
                    TokenStateStore.GracePayload payload = grace.get();
                    return TokenPairVO.bearer(payload.accessToken(), (int) payload.accessExpiresIn(),
                            payload.refreshToken(), (int) payload.refreshExpiresIn());
                }
                try {
                    Thread.sleep(REFRESH_GRACE_POLL_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new BusinessException(ErrorCode.AUTH_REFRESH_CONFLICT);
        }
        revokeFamily(row.getFamilyId(), row.getUserId(), RevokeReason.REUSE_DETECTED);
        throw new BusinessException(ErrorCode.AUTH_REFRESH_REUSED);
    }

    // ------------------------------------------------------------------ 登出

    @Transactional
    public void logout(CurrentUser currentUser, String rawRefreshToken, String jti, Instant accessExpiresAt) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = IdentityCrypto.sha256Hex(rawRefreshToken);
            RefreshToken row = refreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getTokenHash, tokenHash));
            if (row != null && row.getUserId().equals(currentUser.id())) {
                revokeFamily(row.getFamilyId(), currentUser.id(), RevokeReason.LOGOUT);
            }
        }
        blacklistAccess(jti, accessExpiresAt);
    }

    @Transactional
    public void logoutAll(CurrentUser currentUser, String jti, Instant accessExpiresAt) {
        revokeAll(currentUser.id(), RevokeReason.LOGOUT_ALL);
        blacklistAccess(jti, accessExpiresAt);
    }

    public void blacklistAccess(String jti, Instant accessExpiresAt) {
        if (jti == null || accessExpiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), accessExpiresAt);
        if (!ttl.isNegative() && !ttl.isZero()) {
            tokenStateStore.blacklist(jti, ttl);
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 全端吊销（M8 解绑/风控也可复用）。 */
    @Transactional
    public void revokeAll(Long userId, RevokeReason reason) {
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, userId)
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, Times.nowUtc())
                .set(RefreshToken::getRevokeReason, reason));
    }

    public void revokeFamily(String familyId, Long userId, RevokeReason reason) {
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getFamilyId, familyId)
                .eq(RefreshToken::getUserId, userId)
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, Times.nowUtc())
                .set(RefreshToken::getRevokeReason, reason));
    }

    private IssuedRefresh createRefreshToken(Long userId, String familyId, Long parentId,
                                             ClientInfo client, LocalDateTime now) {
        String plain = IdentityCrypto.randomToken();
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(IdentityCrypto.sha256Hex(plain));
        token.setFamilyId(familyId);
        token.setParentId(parentId);
        token.setClientIp(client.ip());
        token.setUserAgent(client.userAgent());
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.getRefreshTtl()));
        refreshTokenMapper.insert(token);
        return new IssuedRefresh(token, plain);
    }

    private TokenPairVO toPair(JwtService.IssuedAccessToken access, String refreshToken) {
        return TokenPairVO.bearer(access.value(), (int) access.expiresIn(),
                refreshToken, (int) jwtService.refreshTtlSeconds());
    }

    private void auditFailure(Long userId, String username, LoginFailureReason reason, ClientInfo client) {
        try {
            auditWriter.recordFailure(userId, username, reason, client);
        } catch (RuntimeException ex) {
            log.warn("Login audit failed, reason={}", reason, ex);
        }
    }

    private User findByUsername(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return user == null || user.isDeleted() ? null : user;
    }

    private BusinessException translateDuplicate(String username, String email) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            return new BusinessException(ErrorCode.USER_USERNAME_TAKEN);
        }
        if (email != null && userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            return new BusinessException(ErrorCode.USER_EMAIL_TAKEN);
        }
        return new BusinessException(ErrorCode.USER_USERNAME_TAKEN);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("@deleted.invalid")) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "邮箱域名不可用", null);
        }
        return normalized;
    }

    private String defaultNickname(String nickname, String username) {
        return nickname == null || nickname.isBlank() ? username : nickname.trim();
    }

    private String uaHash(String userAgent) {
        return IdentityCrypto.sha256Hex(userAgent == null ? "" : userAgent);
    }

    private record IssuedRefresh(RefreshToken entity, String plainToken) {
    }
}