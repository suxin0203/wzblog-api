package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.modules.identity.api.vo.ForgotSentVO;
import com.wzblog.modules.identity.api.vo.ResetTokenVO;
import com.wzblog.modules.identity.domain.RevokeReason;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.VerificationCode;
import com.wzblog.modules.identity.domain.VerificationScene;
import com.wzblog.modules.identity.infra.config.AuthProperties;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.mapper.VerificationCodeMapper;
import com.wzblog.modules.identity.infra.redis.LoginAttemptStore;
import com.wzblog.modules.identity.infra.redis.TokenStateStore;
import com.wzblog.modules.identity.infra.security.IdentityCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 找回密码三步：request（防枚举）→ verify（一次性 resetToken）→ reset（改密 + 全端下线）。
 */
@Service
public class PasswordResetAppService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetAppService.class);
    private static final int CODE_EXPIRE_SECONDS = 300;

    private final UserMapper userMapper;
    private final VerificationCodeMapper verificationCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeSender codeSender;
    private final TokenStateStore tokenStateStore;
    private final LoginAttemptStore attemptStore;
    private final AuthAppService authAppService;
    private final AuthProperties properties;

    public PasswordResetAppService(UserMapper userMapper,
                                   VerificationCodeMapper verificationCodeMapper,
                                   PasswordEncoder passwordEncoder,
                                   VerificationCodeSender codeSender,
                                   TokenStateStore tokenStateStore,
                                   LoginAttemptStore attemptStore,
                                   AuthAppService authAppService,
                                   AuthProperties properties) {
        this.userMapper = userMapper;
        this.verificationCodeMapper = verificationCodeMapper;
        this.passwordEncoder = passwordEncoder;
        this.codeSender = codeSender;
        this.tokenStateStore = tokenStateStore;
        this.attemptStore = attemptStore;
        this.authAppService = authAppService;
        this.properties = properties;
    }

    /**
     * 无论邮箱是否存在都返回成功（防账号枚举）。
     */
    @Transactional
    public ForgotSentVO requestReset(String rawEmail, ClientInfo client) {
        String email = normalizeEmail(rawEmail);
        boolean emailAllowed = attemptStore.tryAcquireForgotEmail(IdentityCrypto.sha256Hex(email));
        boolean ipAllowed = attemptStore.tryAcquireForgotIp(client.ip());
        if (emailAllowed && ipAllowed) {
            User user = findActiveByEmail(email);
            if (user != null) {
                LocalDateTime now = Times.nowUtc();
                verificationCodeMapper.update(null, new LambdaUpdateWrapper<VerificationCode>()
                        .eq(VerificationCode::getScene, VerificationScene.RESET)
                        .eq(VerificationCode::getTarget, email)
                        .isNull(VerificationCode::getConsumedAt)
                        .set(VerificationCode::getConsumedAt, now));

                String code = IdentityCrypto.randomDigitCode(6);
                VerificationCode entity = new VerificationCode();
                entity.setScene(VerificationScene.RESET);
                entity.setTarget(email);
                entity.setCodeHash(codeHash(email, code));
                entity.setExpiresAt(now.plusSeconds(CODE_EXPIRE_SECONDS));
                entity.setAttemptCount(0);
                entity.setCreatedAt(now);
                verificationCodeMapper.insert(entity);
                try {
                    codeSender.sendResetCode(email, code);
                } catch (RuntimeException ex) {
                    log.warn("Failed to send reset code to {}", email, ex);
                }
            }
        }
        return new ForgotSentVO(true, CODE_EXPIRE_SECONDS);
    }

    @Transactional
    public ResetTokenVO verifyCode(String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        VerificationCode latest = verificationCodeMapper.selectOne(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getScene, VerificationScene.RESET)
                .eq(VerificationCode::getTarget, email)
                .isNull(VerificationCode::getConsumedAt)
                .orderByDesc(VerificationCode::getCreatedAt)
                .orderByDesc(VerificationCode::getId)
                .last("LIMIT 1"));
        LocalDateTime now = Times.nowUtc();
        if (latest == null || !latest.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_EXPIRED);
        }
        if (!IdentityCrypto.constantTimeEquals(codeHash(email, code), latest.getCodeHash())) {
            verificationCodeMapper.update(null, new LambdaUpdateWrapper<VerificationCode>()
                    .eq(VerificationCode::getId, latest.getId())
                    .isNull(VerificationCode::getConsumedAt)
                    .lt(VerificationCode::getAttemptCount, properties.getVerificationMaxAttempts())
                    .setSql("attempt_count = attempt_count + 1"));
            VerificationCode reloaded = verificationCodeMapper.selectById(latest.getId());
            if (reloaded != null && reloaded.getAttemptCount() != null
                    && reloaded.getAttemptCount() >= properties.getVerificationMaxAttempts()) {
                verificationCodeMapper.update(null, new LambdaUpdateWrapper<VerificationCode>()
                        .eq(VerificationCode::getId, latest.getId())
                        .isNull(VerificationCode::getConsumedAt)
                        .set(VerificationCode::getConsumedAt, Times.nowUtc()));
                throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_LOCKED,
                        ErrorCode.AUTH_VERIFICATION_CODE_LOCKED.defaultMessage(), 300L);
            }
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_INVALID);
        }

        int consumed = verificationCodeMapper.update(null, new LambdaUpdateWrapper<VerificationCode>()
                .eq(VerificationCode::getId, latest.getId())
                .isNull(VerificationCode::getConsumedAt)
                .gt(VerificationCode::getExpiresAt, Times.nowUtc())
                .set(VerificationCode::getConsumedAt, Times.nowUtc()));
        if (consumed != 1) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_EXPIRED);
        }
        User user = findActiveByEmail(email);
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_EXPIRED);
        }

        String resetToken = IdentityCrypto.randomToken();
        tokenStateStore.saveResetToken(IdentityCrypto.sha256Hex(resetToken), user.getId(),
                properties.getResetTokenTtl());
        return new ResetTokenVO(resetToken, (int) properties.getResetTokenTtl().toSeconds());
    }

    @Transactional
    public void reset(String resetToken, String newPassword) {
        Long userId = tokenStateStore.consumeResetToken(IdentityCrypto.sha256Hex(resetToken));
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_RESET_TOKEN_EXPIRED);
        }
        userMapper.lockById(userId);
        User user = userMapper.selectById(userId);
        if (user == null || user.isDeleted()) {
            throw new BusinessException(ErrorCode.AUTH_RESET_TOKEN_EXPIRED);
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPasswordHash, passwordEncoder.encode(newPassword))
                .setSql("version = version + 1")
                .set(User::getUpdatedAt, Times.nowUtc()));
        authAppService.revokeAll(userId, RevokeReason.LOGOUT_ALL);
    }

    private User findActiveByEmail(String email) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .isNull(User::getDeletedAt)
                .last("LIMIT 1"));
        return user;
    }

    /** SHA-256(target + ":" + code + ":" + pepper)。 */
    private String codeHash(String target, String code) {
        return IdentityCrypto.sha256Hex(target + ":" + code + ":" + properties.getCodePepper());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}