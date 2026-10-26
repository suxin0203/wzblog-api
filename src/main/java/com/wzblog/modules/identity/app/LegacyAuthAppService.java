package com.wzblog.modules.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.common.web.LegacyResponses;
import com.wzblog.modules.identity.api.vo.TokenPairVO;
import com.wzblog.modules.identity.domain.RefreshToken;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.modules.identity.infra.mapper.RefreshTokenMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.security.IdentityCrypto;
import com.wzblog.modules.identity.infra.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 旧协议身份门面（兼容已上线小程序，docs/13 v2 §2.1）：
 * 算术验证码（MD5）、Base64 密码、宽松注册规则（≥4 位）、openid 顺带绑定、
 * refresh 不轮换（小程序沿用旧 refreshToken，轮换会触发家族复用检测踢线）。
 * 全部返回纯类型/Map，避免向 wechat 模块泄漏本模块 domain 类型（ArchUnit）。
 */
@Service
public class LegacyAuthAppService {

    private static final Logger log = LoggerFactory.getLogger(LegacyAuthAppService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    /** Node 版未设头像时的默认微信头像（小程序端展示兼容）。 */
    private static final String DEFAULT_AVATAR_URL =
            "https://thirdwx.qlogo.cn/mmopen/vi_32/POgEwh4mIHO4nibH0KlMECNjjGxQUq24ZEaGT4poC6icRiccVGKSyXwibcPq4BWmiaIGuG1icwxaQX6grC9VemZoJ8rg/132";

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthAppService authAppService;
    private final WechatIdentityService wechatIdentityService;
    private final LegacyArithmeticCaptcha captcha;
    private final String avatarBaseUrl;

    public LegacyAuthAppService(UserMapper userMapper, RefreshTokenMapper refreshTokenMapper,
                                PasswordEncoder passwordEncoder, JwtService jwtService,
                                AuthAppService authAppService,
                                WechatIdentityService wechatIdentityService,
                                LegacyArithmeticCaptcha captcha,
                                @Value("${wzblog.file.public-base-url:http://localhost:8021/media}")
                                String avatarBaseUrl) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authAppService = authAppService;
        this.wechatIdentityService = wechatIdentityService;
        this.captcha = captcha;
        this.avatarBaseUrl = avatarBaseUrl;
    }

    /** 纯字段 user（snake_case 由 LegacyResponses 承担，这里给原始值）。 */
    public record LegacyUser(Long id, String username, String email, String nickname,
                             String avatarKey, String role, boolean root, String status,
                             String bio, LocalDateTime createdAt, LocalDateTime updatedAt,
                             LocalDateTime lastLoginAt, String openid) {
    }

    public record LoginResult(LegacyUser user, TokenPairVO tokens) {
    }

    // ------------------------------------------------------------ 验证码

    public LegacyArithmeticCaptcha.Captcha captcha() {
        return captcha.generate();
    }

    // ------------------------------------------------------------ 注册 / 登录

    /** 旧规则注册：用户名 4~20 [A-Za-z0-9_]、密码 ≥4、邮箱必填；openid 顺带绑定（Node 忽略此字段的缺陷在此修复）。 */
    @Transactional
    public LoginResult register(String username, String rawPassword, String email, String openid,
                                String captchaId, String captchaAnswerMd5, ClientInfo client) {
        captcha.verify(captchaId, captchaAnswerMd5);
        return registerAfterCaptcha(username, rawPassword, email, openid, client);
    }

    /** 扫码注册入口（register-and-confirm）：旧系统该端点无验证码。 */
    @Transactional
    public LoginResult registerWithoutCaptcha(String username, String rawPassword, String email,
                                              String openid, ClientInfo client) {
        return registerAfterCaptcha(username, rawPassword, email, openid, client);
    }

    private LoginResult registerAfterCaptcha(String username, String rawPassword, String email,
                                             String openid, ClientInfo client) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "用户名需为 4~20 位字母数字下划线", null);
        }
        String password = rawPassword == null ? "" : rawPassword.trim();
        if (password.length() < 4) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "密码至少 4 个字符", null);
        }
        // 邮箱可选：/users/register 端由前端必填；扫码注册端（register-and-confirm）可不带
        String emailStr = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!emailStr.isEmpty() && !EMAIL_PATTERN.matcher(emailStr).matches()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "邮箱格式不正确", null);
        }
        if (emailStr.isEmpty()) {
            emailStr = null;
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, name)) > 0) {
            throw new BusinessException(ErrorCode.AUTH_USERNAME_TAKEN);
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, emailStr)) > 0) {
            throw new BusinessException(ErrorCode.USER_EMAIL_TAKEN);
        }
        User created = new User();
        created.setUsername(name);
        created.setPasswordHash(passwordEncoder.encode(password));
        created.setEmail(emailStr);
        created.setNickname(name);
        created.setRole(com.wzblog.common.security.Role.USER);
        created.setStatus(UserStatus.ACTIVE);
        created.setSource(com.wzblog.modules.identity.domain.UserSource.WEB);
        userMapper.insert(created);
        if (openid != null && !openid.isBlank()) {
            try {
                wechatIdentityService.bindWechat(created.getId(), openid.trim(), null);
            } catch (BusinessException ex) {
                log.warn("注册时绑定 openid 失败（不影响注册）userId={}：{}", created.getId(),
                        ex.errorCode().code());
            }
        }
        TokenPairVO tokens = authAppService.issueTokens(created.getId(), client);
        return new LoginResult(toLegacyUser(userMapper.selectById(created.getId())), tokens);
    }

    /** 旧规则登录：算术验证码 + Base64 密码 + openid 顺带绑定（绑定失败不影响登录，对齐旧系统宽松行为）。 */
    @Transactional
    public LoginResult login(String username, String rawPassword, String openid,
                             String captchaId, String captchaAnswerMd5, ClientInfo client) {
        captcha.verify(captchaId, captchaAnswerMd5);
        String name = username == null ? "" : username.trim();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
        if (user == null || !passwordEncoder.matches(rawPassword == null ? "" : rawPassword.trim(),
                user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (openid != null && !openid.isBlank()) {
            try {
                wechatIdentityService.bindWechat(user.getId(), openid.trim(), null);
            } catch (BusinessException ex) {
                log.warn("登录时绑定 openid 失败（不影响登录）userId={}：{}", user.getId(),
                        ex.errorCode().code());
            }
        }
        TokenPairVO tokens = authAppService.issueTokens(user.getId(), client);
        return new LoginResult(toLegacyUser(userMapper.selectById(user.getId())), tokens);
    }

    // ------------------------------------------------------------ 刷新（不轮换）

    /**
     * 旧语义刷新：只换 access token，refreshToken 不轮换、不落新行
     * （小程序刷新后沿用旧 refreshToken；Java 家族轮换会触发复用检测把用户踢线）。
     */
    @Transactional
    public LegacyRefresh refresh(String refreshTokenRaw) {
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        String hash = IdentityCrypto.sha256Hex(refreshTokenRaw);
        RefreshToken row = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getTokenHash, hash));
        LocalDateTime now = Times.nowUtc();
        if (row == null || row.isRevoked() || !row.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        User user = userMapper.selectById(row.getUserId());
        if (user == null || user.isDeleted() || user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        JwtService.IssuedAccessToken access = jwtService.issue(user.getId(), user.getRole());
        return new LegacyRefresh(access.value(), toLegacyUser(user));
    }

    public record LegacyRefresh(String accessToken, LegacyUser user) {
    }

    // ------------------------------------------------------------ 供 wechat 兼容层调用

    /** 按 id 出旧格式 user map（扫码轮询 confirmed 分支用）。 */
    @Transactional
    public Map<String, Object> userMapById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Map.of();
        }
        return toMap(toLegacyUser(user));
    }

    /** openid 查已绑定用户（不创建，对齐旧 /wechat/userinfo 语义）。 */
    @Transactional
    public LegacyUser userByOpenid(String openid) {
        User user = userMapper.selectByOpenid(openid);
        return user == null ? null : toLegacyUser(user);
    }

    /** 账号密码校验（bind-and-confirm 用），失败返回 null。 */
    @Transactional
    public LegacyUser verifyCredentials(String username, String rawPassword) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username == null ? "" : username.trim()));
        if (user == null || user.isDeleted()) {
            return null;
        }
        if (!passwordEncoder.matches(rawPassword == null ? "" : rawPassword.trim(), user.getPasswordHash())) {
            return null;
        }
        return toLegacyUser(user);
    }

    @Transactional
    public void bindOpenid(Long userId, String openid) {
        wechatIdentityService.bindWechat(userId, openid, null);
    }

    /** 旧格式 user map（snake_case + avatar_url 绝对地址 + refresh_token 占位由调用方补）。 */
    public Map<String, Object> toMap(LegacyUser user) {
        String avatarUrl = user.avatarKey() == null || user.avatarKey().isBlank()
                ? DEFAULT_AVATAR_URL
                : avatarBaseUrl + "/" + user.avatarKey();
        return LegacyResponses.legacyUser(user.id(), user.username(), user.email(),
                user.nickname(), user.avatarKey(), avatarUrl, user.role(), user.root() ? 1 : 0,
                user.status(), user.bio(), user.createdAt(), user.updatedAt(), user.lastLoginAt());
    }

    /** /users/me：user + 统计（对齐旧 getCurrentUser）。 */
    @Transactional
    public Map<String, Object> me(CurrentUser current) {
        User user = userMapper.selectById(current.id());
        if (user == null || user.isDeleted()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        Map<String, Object> data = toMap(toLegacyUser(user));
        data.put("openid", user.getOpenid());
        return data;
    }

    private LegacyUser toLegacyUser(User user) {
        return new LegacyUser(user.getId(), user.getUsername(), user.getEmail(), user.getNickname(),
                user.getAvatarKey(), roleLabel(user),
                user.getRole() == com.wzblog.common.security.Role.ADMIN,
                user.getStatus().name(), user.getBio(), user.getCreatedAt(), user.getUpdatedAt(),
                user.getLastLoginAt(), user.getOpenid());
    }

    private static String roleLabel(User user) {
        return switch (user.getRole()) {
            case ADMIN -> "admin";
            case EDITOR -> "editor";
            default -> "user";
        };
    }

    /** data 里补 refresh_token 字段（小程序从 userinfo 的 data.refresh_token 读取刷新凭证）。 */
    public Map<String, Object> toMapWithRefresh(LegacyUser user, String refreshToken) {
        Map<String, Object> map = new LinkedHashMap<>(toMap(user));
        map.put("refresh_token", refreshToken);
        return map;
    }
}
