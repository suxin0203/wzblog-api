package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.AdminCreateUserRequest;
import com.wzblog.modules.identity.api.dto.AdminUpdateUserRequest;
import com.wzblog.modules.identity.api.vo.AdminUserVO;
import com.wzblog.modules.identity.api.vo.LoginAuditVO;
import com.wzblog.modules.identity.domain.LoginAudit;
import com.wzblog.modules.identity.domain.RevokeReason;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.UserSource;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.modules.identity.infra.mapper.LoginAuditMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.redis.TokenStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 管理员用户管理（ADMIN）：分页/详情/创建/更新/注销/登录审计。
 */
@Service
public class AdminUserAppService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserAppService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserMapper userMapper;
    private final LoginAuditMapper loginAuditMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserVoAssembler assembler;
    private final AuthAppService authAppService;
    private final TokenStateStore tokenStateStore;

    public AdminUserAppService(UserMapper userMapper,
                               LoginAuditMapper loginAuditMapper,
                               PasswordEncoder passwordEncoder,
                               UserVoAssembler assembler,
                               AuthAppService authAppService,
                               TokenStateStore tokenStateStore) {
        this.userMapper = userMapper;
        this.loginAuditMapper = loginAuditMapper;
        this.passwordEncoder = passwordEncoder;
        this.assembler = assembler;
        this.authAppService = authAppService;
        this.tokenStateStore = tokenStateStore;
    }

    public PageResult<AdminUserVO> list(String keyword, Role role, UserStatus status,
                                        Integer page, Integer pageSize) {
        int currentPage = validPage(page);
        int size = validPageSize(pageSize);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .isNull(User::getDeletedAt);
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 50) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "keyword 长度不能超过 50", null);
            }
            String kw = keyword.trim();
            wrapper.and(w -> w.likeRight(User::getUsername, kw).or().likeRight(User::getNickname, kw));
        }
        if (role != null) {
            wrapper.eq(User::getRole, role);
        }
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getCreatedAt).orderByDesc(User::getId);
        IPage<User> result = userMapper.selectPage(new Page<>(currentPage, size), wrapper);
        List<AdminUserVO> list = result.getRecords().stream().map(assembler::toAdmin).toList();
        return PageResult.of(list, result.getTotal(), currentPage, size);
    }

    public AdminUserVO detail(Long id) {
        return assembler.toAdmin(requireUser(id));
    }

    @Transactional
    public AdminUserVO create(AdminCreateUserRequest request) {
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
        user.setNickname(request.nickname().trim());
        user.setRole(request.role() == null ? Role.USER : request.role());
        user.setStatus(request.status() == null ? UserStatus.ACTIVE : request.status());
        user.setSource(UserSource.WEB);
        user.setVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.USER_USERNAME_TAKEN);
        }
        return assembler.toAdmin(userMapper.selectById(user.getId()));
    }

    @Transactional
    public AdminUserVO update(CurrentUser admin, Long id, AdminUpdateUserRequest request) {
        User target = requireUser(id);
        boolean self = admin.id().equals(id);
        if (self && (request.role() != target.getRole() || request.status() != target.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "不能修改自己的角色或状态", null);
        }
        boolean demotesAdmin = target.getRole() == Role.ADMIN
                && (request.role() != Role.ADMIN || request.status() != UserStatus.ACTIVE);
        if (demotesAdmin && userMapper.countOtherActiveAdmins(id) == 0) {
            throw new BusinessException(ErrorCode.USER_LAST_ADMIN);
        }
        String email = normalizeEmail(request.email());
        if (email != null && userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email).ne(User::getId, id)) > 0) {
            throw new BusinessException(ErrorCode.USER_EMAIL_TAKEN);
        }

        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(User::getNickname, request.nickname().trim())
                .set(User::getRole, request.role())
                .set(User::getStatus, request.status())
                .setSql("version = version + 1")
                .set(User::getUpdatedAt, Times.nowUtc());
        if (email != null) {
            update.set(User::getEmail, email);
        }
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            update.set(User::getPasswordHash, passwordEncoder.encode(request.newPassword()));
        }
        userMapper.update(null, update);

        if ((request.newPassword() != null && !request.newPassword().isBlank())
                || request.status() == UserStatus.DISABLED) {
            authAppService.revokeAll(id, RevokeReason.ADMIN);
        }
        return assembler.toAdmin(requireUser(id));
    }

    /**
     * 注销：软删 + 匿名化 + 吊销全部 family；文章保留。
     */
    @Transactional
    public void delete(CurrentUser admin, Long id) {
        if (admin.id().equals(id)) {
            throw new BusinessException(ErrorCode.USER_CANNOT_DELETE_SELF);
        }
        Long before = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getId, id));
        if (before == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        boolean guarded = tokenStateStore.tryAcquireAdminGuard();
        if (!guarded) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "操作冲突，请稍后重试", 5L);
        }
        try {
            userMapper.lockById(id);
            User target = userMapper.selectById(id);
            if (target == null || target.isDeleted()) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            }
            if (target.getRole() == Role.ADMIN && userMapper.countOtherActiveAdmins(id) == 0) {
                throw new BusinessException(ErrorCode.USER_LAST_ADMIN);
            }
            LocalDateTime now = Times.nowUtc();
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, id)
                    .isNull(User::getDeletedAt)
                    .set(User::getUsername, "deleted_" + id)
                    .set(User::getEmail, "deleted_" + id + "@deleted.invalid")
                    .set(User::getOpenid, "deleted_" + id)
                    .set(User::getUnionid, null)
                    .set(User::getNickname, "已注销用户")
                    .set(User::getAvatarKey, null)
                    .set(User::getBio, null)
                    .set(User::getPasswordHash, passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                    .set(User::getStatus, UserStatus.DISABLED)
                    .set(User::getDeletedAt, now)
                    .set(User::getUpdatedAt, now)
                    .setSql("version = version + 1"));
            authAppService.revokeAll(id, RevokeReason.ADMIN);
        } finally {
            tokenStateStore.releaseAdminGuard();
        }
        log.info("Admin {} deleted user {}", admin.id(), id);
    }

    public PageResult<LoginAuditVO> loginAudits(Long userId, Boolean success, Instant from, Instant to,
                                                Integer page, Integer pageSize) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        int currentPage = validPage(page);
        int size = validPageSize(pageSize);
        LambdaQueryWrapper<LoginAudit> wrapper = new LambdaQueryWrapper<LoginAudit>()
                .eq(LoginAudit::getUserId, userId);
        if (success != null) {
            wrapper.eq(LoginAudit::getSuccess, success);
        }
        if (from != null) {
            wrapper.ge(LoginAudit::getCreatedAt, Times.toUtc(from));
        }
        if (to != null) {
            wrapper.lt(LoginAudit::getCreatedAt, Times.toUtc(to));
        }
        wrapper.orderByDesc(LoginAudit::getCreatedAt).orderByDesc(LoginAudit::getId);
        IPage<LoginAudit> result = loginAuditMapper.selectPage(new Page<>(currentPage, size), wrapper);
        List<LoginAuditVO> list = result.getRecords().stream()
                .map(audit -> new LoginAuditVO(
                        audit.getId(),
                        audit.getUserId(),
                        audit.getUsernameAttempt(),
                        Boolean.TRUE.equals(audit.getSuccess()),
                        audit.getFailureReason() == null ? null : audit.getFailureReason().name(),
                        audit.getIp(),
                        audit.getUserAgent(),
                        Times.toInstant(audit.getCreatedAt())))
                .toList();
        return PageResult.of(list, result.getTotal(), currentPage, size);
    }

    private User requireUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null || user.isDeleted()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private int validPage(Integer page) {
        int value = page == null ? 1 : page;
        if (value < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "page 必须大于等于 1", null);
        }
        return value;
    }

    private int validPageSize(Integer pageSize) {
        int value = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "pageSize 取值范围为 1~100", null);
        }
        return value;
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
}