package com.wzblog.modules.identity.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * M1 认证配置，集中在 {@code wzblog.auth.*} 命名空间（00 §7）。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.auth")
public class AuthProperties {

    /** HS256 密钥，至少 256bit；生产必须通过 JWT_SECRET 注入。 */
    private String jwtSecret = "wzblog-dev-only-secret-key-please-override-0123456789";
    private String issuer = "wzblog";
    private String audience = "wzblog-web";
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofDays(14);
    private Duration refreshGrace = Duration.ofSeconds(10);

    private boolean captchaEnabled = true;
    private Duration captchaTtl = Duration.ofMinutes(5);
    private int captchaMaxFails = 3;

    private int loginMaxFailures = 5;
    private Duration loginLockTtl = Duration.ofMinutes(15);
    private int loginIpLimitPerMinute = 30;

    private Duration resetTokenTtl = Duration.ofMinutes(5);
    private String codePepper = "wzblog-dev-code-pepper";
    private int verificationMaxAttempts = 3;
    private int registerIpLimitPerHour = 10;
    private int forgotEmailLimitPerHour = 5;
    private int forgotIpLimitPerHour = 10;

    private String avatarPublicBaseUrl = "http://localhost:8021/media";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public Duration getRefreshGrace() {
        return refreshGrace;
    }

    public void setRefreshGrace(Duration refreshGrace) {
        this.refreshGrace = refreshGrace;
    }

    public boolean isCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }

    public Duration getCaptchaTtl() {
        return captchaTtl;
    }

    public void setCaptchaTtl(Duration captchaTtl) {
        this.captchaTtl = captchaTtl;
    }

    public int getCaptchaMaxFails() {
        return captchaMaxFails;
    }

    public void setCaptchaMaxFails(int captchaMaxFails) {
        this.captchaMaxFails = captchaMaxFails;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getLoginLockTtl() {
        return loginLockTtl;
    }

    public void setLoginLockTtl(Duration loginLockTtl) {
        this.loginLockTtl = loginLockTtl;
    }

    public int getLoginIpLimitPerMinute() {
        return loginIpLimitPerMinute;
    }

    public void setLoginIpLimitPerMinute(int loginIpLimitPerMinute) {
        this.loginIpLimitPerMinute = loginIpLimitPerMinute;
    }

    public Duration getResetTokenTtl() {
        return resetTokenTtl;
    }

    public void setResetTokenTtl(Duration resetTokenTtl) {
        this.resetTokenTtl = resetTokenTtl;
    }

    public String getCodePepper() {
        return codePepper;
    }

    public void setCodePepper(String codePepper) {
        this.codePepper = codePepper;
    }

    public int getRegisterIpLimitPerHour() {
        return registerIpLimitPerHour;
    }

    public int getVerificationMaxAttempts() {
        return verificationMaxAttempts;
    }

    public void setVerificationMaxAttempts(int verificationMaxAttempts) {
        this.verificationMaxAttempts = verificationMaxAttempts;
    }

    public void setRegisterIpLimitPerHour(int registerIpLimitPerHour) {
        this.registerIpLimitPerHour = registerIpLimitPerHour;
    }

    public int getForgotEmailLimitPerHour() {
        return forgotEmailLimitPerHour;
    }

    public void setForgotEmailLimitPerHour(int forgotEmailLimitPerHour) {
        this.forgotEmailLimitPerHour = forgotEmailLimitPerHour;
    }

    public int getForgotIpLimitPerHour() {
        return forgotIpLimitPerHour;
    }

    public void setForgotIpLimitPerHour(int forgotIpLimitPerHour) {
        this.forgotIpLimitPerHour = forgotIpLimitPerHour;
    }

    public String getAvatarPublicBaseUrl() {
        return avatarPublicBaseUrl;
    }

    public void setAvatarPublicBaseUrl(String avatarPublicBaseUrl) {
        this.avatarPublicBaseUrl = avatarPublicBaseUrl;
    }
}