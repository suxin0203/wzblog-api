package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.UserSource;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 微信身份服务（M8 设计文档 §7）：openid 登录/自动建号、注册绑定、换绑迁移。
 * <p>M8 不写 users 表——所有身份写入集中在此（M1 域内），锁序恒为
 * 目标账号 → 自动账号 → openid 行（条件更新），杜绝双账号抢同一 openid。</p>
 */
@Service
public class WechatIdentityService {

    private static final Logger log = LoggerFactory.getLogger(WechatIdentityService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTO_USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public WechatIdentityService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 登录或创建结果；status 供调用方决定是否签发 Token。 */
    public record WechatIdentity(Long userId, UserStatus status, boolean created) {
    }

    /**
     * 按 openid 登录；不存在则创建正式 USER（source=WECHAT，随机用户名/密码）。
     * 并发下的 uk_openid 冲突由唯一键兜底：捕获后回查走登录分支，不产生双账号。
     */
    @Transactional
    public WechatIdentity loginOrCreateByWechat(String openid, String unionid, String nickname,
                                                 String avatarKey) {
        User existing = userMapper.selectByOpenid(openid);
        if (existing != null) {
            if (existing.isDeleted()) {
                throw new BusinessException(ErrorCode.WECHAT_ACCOUNT_DELETED);
            }
            if (existing.getStatus() == UserStatus.DISABLED) {
                return new WechatIdentity(existing.getId(), UserStatus.DISABLED, false);
            }
            // unionid 有值则补写；属其他账号时按冲突拒绝
            if (unionid != null && !unionid.isBlank()
                    && !Objects.equals(unionid, existing.getUnionid())) {
                requireUnionidFree(unionid, existing.getId());
                userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getId, existing.getId())
                        .set(User::getUnionid, unionid)
                        .set(User::getUpdatedAt, Times.nowUtc()));
            }
            return new WechatIdentity(existing.getId(), UserStatus.ACTIVE, false);
        }

        requireUnionidFree(unionid, null);
        User created = new User();
        created.setUsername(uniqueAutoUsername());
        created.setPasswordHash(passwordEncoder.encode(randomToken()));
        created.setNickname(nickname == null || nickname.isBlank()
                ? "微信用户" + randomDigits(4) : nickname.trim());
        created.setAvatarKey(avatarKey);
        created.setRole(com.wzblog.common.security.Role.USER);
        created.setStatus(UserStatus.ACTIVE);
        created.setSource(UserSource.WECHAT);
        created.setOpenid(openid);
        created.setUnionid(unionid);
        try {
            userMapper.insert(created);
            return new WechatIdentity(created.getId(), UserStatus.ACTIVE, true);
        } catch (DuplicateKeyException ex) {
            // 并发同 openid：另一请求先建号，回查走登录分支
            User winner = userMapper.selectByOpenid(openid);
            if (winner == null) {
                throw ex;
            }
            return new WechatIdentity(winner.getId(), winner.getStatus(), false);
        }
    }

    /**
     * 扫码注册并绑定（source=QR）：新建正式账号，openid 若属自动账号则匿名化释放；
     * 属其他正式账号 → 冲突。用户名冲突 → AUTH_USERNAME_TAKEN。
     */
    @Transactional
    public Long registerAndBindWechat(String username, String password, String nickname,
                                      String openid, String unionid) {
        User owner = userMapper.selectByOpenid(openid);
        if (owner != null) {
            if (owner.isDeleted()) {
                anonymize(owner);
            } else if (owner.getSource() == UserSource.WECHAT) {
                anonymize(owner);
            } else {
                throw new BusinessException(ErrorCode.WECHAT_ALREADY_BOUND);
            }
        }
        requireUnionidFree(unionid, null);

        User created = new User();
        created.setUsername(username.trim().toLowerCase(Locale.ROOT));
        created.setPasswordHash(passwordEncoder.encode(password));
        created.setNickname(nickname == null || nickname.isBlank() ? username.trim() : nickname.trim());
        created.setRole(com.wzblog.common.security.Role.USER);
        created.setStatus(UserStatus.ACTIVE);
        created.setSource(UserSource.QR);
        created.setOpenid(openid);
        created.setUnionid(unionid);
        try {
            userMapper.insert(created);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.AUTH_USERNAME_TAKEN);
        }
        return created.getId();
    }

    /**
     * 已登录用户绑定 openid：同 openid 幂等；绑其他 openid → 不支持换绑；
     * openid 被自动账号占用则匿名化迁移，被正式账号占用 → 冲突。
     */
    @Transactional
    public void bindWechat(Long userId, String openid, String unionid) {
        User target = userMapper.selectById(userId);
        if (target == null || target.isDeleted()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (Objects.equals(target.getOpenid(), openid)) {
            return; // 幂等：重复绑同一 openid
        }
        if (target.getOpenid() != null) {
            throw new BusinessException(ErrorCode.WECHAT_ACCOUNT_ALREADY_BOUND);
        }
        User owner = userMapper.selectByOpenid(openid);
        if (owner != null && !owner.getId().equals(userId)) {
            if (owner.isDeleted() || owner.getSource() == UserSource.WECHAT) {
                anonymize(owner);
            } else {
                throw new BusinessException(ErrorCode.WECHAT_ALREADY_BOUND);
            }
        }
        requireUnionidFree(unionid, userId);
        int updated = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .isNull(User::getOpenid)
                .set(User::getOpenid, openid)
                .set(User::getUnionid, unionid)
                .set(User::getUpdatedAt, Times.nowUtc()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.WECHAT_ALREADY_BOUND); // 并发抢占
        }
    }

    // ------------------------------------------------------------ 内部

    private void requireUnionidFree(String unionid, Long excludeUserId) {
        if (unionid == null || unionid.isBlank()) {
            return;
        }
        User owner = userMapper.selectByUnionid(unionid);
        if (owner != null && !Objects.equals(owner.getId(), excludeUserId)) {
            throw new BusinessException(ErrorCode.WECHAT_UNIONID_CONFLICT);
        }
    }

    /**
     * 匿名化自动账号：username/openid 改写为 deleted_{id}（唯一键安全）、unionid 清空、软删。
     * 条件更新 WHERE id=? AND openid=? 保证并发迁移不误伤。
     */
    private void anonymize(User autoAccount) {
        String marker = "deleted_" + autoAccount.getId();
        int updated = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, autoAccount.getId())
                .eq(User::getOpenid, autoAccount.getOpenid())
                .set(User::getUsername, marker)
                .set(User::getOpenid, marker)
                .set(User::getUnionid, null)
                .set(User::getDeletedAt, Times.nowUtc())
                .set(User::getUpdatedAt, Times.nowUtc()));
        if (updated > 0) {
            log.info("微信自动账号已匿名化 userId={}", autoAccount.getId());
        }
    }

    private String uniqueAutoUsername() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String candidate = "wx_" + randomDigits(12);
            if (userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<User>().eq(User::getUsername, candidate)) == null) {
                return candidate;
            }
        }
        return "wx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(AUTO_USERNAME_ALPHABET.charAt(RANDOM.nextInt(10)));
        }
        return builder.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
