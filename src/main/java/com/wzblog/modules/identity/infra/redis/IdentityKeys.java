package com.wzblog.modules.identity.infra.redis;

/**
 * M1 Redis key 常量，逐字取自 M1 模块文档 §6.2。
 */
public final class IdentityKeys {

    private static final String CAPTCHA = "identity:captcha:";
    private static final String LOGIN_FAIL = "identity:login:fail:";
    private static final String LOGIN_LOCK = "identity:login:lock:";
    private static final String LOGIN_DAY = "identity:login:day:";
    private static final String TOKEN_BLACKLIST = "identity:token:blacklist:";
    private static final String RESET = "auth:reset:";
    private static final String REFRESH_GRACE = "identity:refresh:grace:";
    private static final String LOGIN_IP = "identity:login:ip:";
    private static final String REGISTER_IP = "identity:register:ip:";
    private static final String FORGOT_EMAIL = "identity:forgot:email:";
    private static final String FORGOT_IP = "identity:forgot:ip:";
    private static final String ADMIN_GUARD = "identity:admin:guard";

    private IdentityKeys() {
    }

    public static String captcha(String captchaId) {
        return CAPTCHA + captchaId;
    }

    public static String loginFail(String usernameHash) {
        return LOGIN_FAIL + usernameHash;
    }

    public static String loginLock(String usernameHash) {
        return LOGIN_LOCK + usernameHash;
    }

    public static String loginDay(Long userId, String businessDate) {
        return LOGIN_DAY + userId + ":" + businessDate;
    }

    public static String tokenBlacklist(String jti) {
        return TOKEN_BLACKLIST + jti;
    }

    public static String reset(String tokenHash) {
        return RESET + tokenHash;
    }

    public static String refreshGrace(String tokenHash) {
        return REFRESH_GRACE + tokenHash;
    }

    public static String loginIp(String ip) {
        return LOGIN_IP + ip;
    }

    public static String registerIp(String ip) {
        return REGISTER_IP + ip;
    }

    public static String forgotEmail(String emailHash) {
        return FORGOT_EMAIL + emailHash;
    }

    public static String forgotIp(String ip) {
        return FORGOT_IP + ip;
    }

    public static String adminGuard() {
        return ADMIN_GUARD;
    }
}