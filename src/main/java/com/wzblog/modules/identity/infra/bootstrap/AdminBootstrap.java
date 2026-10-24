package com.wzblog.modules.identity.infra.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.UserSource;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.modules.identity.infra.config.AdminProperties;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 管理员引导（M0 §15）：不存在则创建 ADMIN；已存在仅提升角色；幂等、不写积分表。
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminProperties properties;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(AdminProperties properties, UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = trimToNull(properties.getUsername());
        String password = trimToNull(properties.getPassword());
        if (username == null) {
            log.debug("Admin bootstrap skipped: WZBLOG_ADMIN_USERNAME not set");
            return;
        }
        if (password == null) {
            log.warn("Admin bootstrap skipped: WZBLOG_ADMIN_PASSWORD not set");
            return;
        }
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null) {
            if (existing.isDeleted()) {
                log.warn("Admin bootstrap skipped: username {} belongs to a deleted account", username);
                return;
            }
            if (existing.getRole() != Role.ADMIN) {
                userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getId, existing.getId())
                        .set(User::getRole, Role.ADMIN)
                        .set(User::getUpdatedAt, Times.nowUtc()));
                log.info("Admin bootstrap promoted existing user {} to ADMIN", username);
            }
            return;
        }
        String email = trimToNull(properties.getEmail());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            Long emailCount = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
            if (emailCount != null && emailCount > 0) {
                log.warn("Admin bootstrap skipped: email {} is already used", email);
                return;
            }
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setEmail(email);
        admin.setNickname(username);
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSource(UserSource.WEB);
        admin.setVersion(0);
        admin.setCreatedAt(Times.nowUtc());
        admin.setUpdatedAt(Times.nowUtc());
        userMapper.insert(admin);
        log.info("Admin bootstrap created administrator {}", username);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}