package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.ChangePasswordRequest;
import com.wzblog.modules.identity.api.dto.UpdateProfileRequest;
import com.wzblog.modules.identity.api.vo.PublicUserProfileVO;
import com.wzblog.modules.identity.api.vo.SessionVO;
import com.wzblog.modules.identity.api.vo.UserSelfVO;
import com.wzblog.modules.identity.domain.RefreshToken;
import com.wzblog.modules.identity.domain.RevokeReason;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.infra.mapper.RefreshTokenMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前用户资料、改密与设备会话。
 */
@Service
public class UserAppService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserVoAssembler assembler;
    private final AuthAppService authAppService;
    private final FileReferencePort fileReferencePort;

    public UserAppService(UserMapper userMapper,
                          RefreshTokenMapper refreshTokenMapper,
                          PasswordEncoder passwordEncoder,
                          UserVoAssembler assembler,
                          AuthAppService authAppService,
                          FileReferencePort fileReferencePort) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.assembler = assembler;
        this.authAppService = authAppService;
        this.fileReferencePort = fileReferencePort;
    }

    public UserSelfVO me(CurrentUser currentUser) {
        return assembler.toSelf(requireUser(currentUser.id()));
    }

    @Transactional
    public UserSelfVO updateProfile(CurrentUser currentUser, UpdateProfileRequest request) {
        User user = requireUser(currentUser.id());
        String bio = request.bio() == null || request.bio().isBlank() ? null : request.bio().trim();
        String avatarKey = request.avatarKey() == null || request.avatarKey().isBlank()
                ? null : request.avatarKey().trim();
        // 头像引用计数：先 claim 新头像（不存在即 4xx），更新成功后 release 旧头像
        if (avatarKey != null) {
            fileReferencePort.claimByKey(avatarKey, user.getId(), currentUser, FileBizType.AVATAR);
        }
        String previousAvatar = user.getAvatarKey();
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getNickname, request.nickname().trim())
                .set(User::getBio, bio)
                .set(User::getAvatarKey, avatarKey)
                .setSql("version = version + 1")
                .set(User::getUpdatedAt, Times.nowUtc()));
        if (previousAvatar != null && !previousAvatar.equals(avatarKey)) {
            fileReferencePort.releaseByKey(previousAvatar);
        }
        return assembler.toSelf(requireUser(user.getId()));
    }

    @Transactional
    public void changePassword(CurrentUser currentUser, ChangePasswordRequest request,
                               String jti, Instant accessExpiresAt) {
        User user = requireUser(currentUser.id());
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_OLD_PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_UNCHANGED);
        }
        userMapper.lockById(user.getId());
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .setSql("version = version + 1")
                .set(User::getUpdatedAt, Times.nowUtc()));
        authAppService.revokeAll(user.getId(), RevokeReason.LOGOUT_ALL);
        authAppService.blacklistAccess(jti, accessExpiresAt);
    }

    public List<SessionVO> sessions(CurrentUser currentUser) {
        List<RefreshToken> rows = refreshTokenMapper.selectList(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, currentUser.id())
                .isNull(RefreshToken::getRevokedAt)
                .gt(RefreshToken::getExpiresAt, Times.nowUtc())
                .orderByDesc(RefreshToken::getIssuedAt));
        Map<String, List<RefreshToken>> byFamily = new LinkedHashMap<>();
        for (RefreshToken row : rows) {
            byFamily.computeIfAbsent(row.getFamilyId(), key -> new ArrayList<>()).add(row);
        }
        List<SessionVO> sessions = new ArrayList<>(byFamily.size());
        for (Map.Entry<String, List<RefreshToken>> entry : byFamily.entrySet()) {
            List<RefreshToken> familyRows = entry.getValue();
            RefreshToken latest = familyRows.get(0);
            LocalDateTime earliest = latest.getIssuedAt();
            LocalDateTime latestIssued = latest.getIssuedAt();
            LocalDateTime latestExpires = latest.getExpiresAt();
            for (RefreshToken row : familyRows) {
                if (row.getIssuedAt().isBefore(earliest)) {
                    earliest = row.getIssuedAt();
                }
                if (row.getIssuedAt().isAfter(latestIssued)) {
                    latestIssued = row.getIssuedAt();
                    latestExpires = row.getExpiresAt();
                }
            }
            sessions.add(new SessionVO(entry.getKey(), latest.getClientIp(), latest.getUserAgent(),
                    Times.toInstant(earliest), Times.toInstant(latestIssued), Times.toInstant(latestExpires)));
        }
        return sessions;
    }

    @Transactional
    public void deleteSession(CurrentUser currentUser, String sessionId) {
        int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, currentUser.id())
                .eq(RefreshToken::getFamilyId, sessionId)
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, Times.nowUtc())
                .set(RefreshToken::getRevokeReason, RevokeReason.LOGOUT));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.USER_SESSION_NOT_FOUND);
        }
    }

    public PublicUserProfileVO publicProfile(Long id) {
        User user = userMapper.selectById(id);
        if (user == null || user.isDeleted()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return assembler.toPublic(user);
    }

    private User requireUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null || user.isDeleted()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}