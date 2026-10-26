package com.wzblog.modules.wechat.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M8 微信模块配置（wzblog.wechat.*）。appid/secret 任一缺失 → 模块关闭（503 WECHAT_NOT_ENABLED）。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.wechat")
public class WechatProperties {

    /** 小程序凭据；双认 WX_MINIAPP_APPID 与 WECHAT_APP_ID 两个环境变量名。 */
    private String appId;
    private String appSecret;

    /** 上游实现：mock | real（凭据缺失时强制降级 mock）。 */
    private String provider = "mock";

    /** 小程序码指向的已发布页面路径（getwxacodeunlimit 的 page）。 */
    private String qrPage = "pages/pc-qr-login/index";

    /** 码版本：release/trial/develop；请求参数可覆盖（旧系统"版本切换"功能）。 */
    private String envVersion = "release";

    /** 扫码会话 TTL（旧系统 5 分钟）。 */
    private int sessionTtlMinutes = 5;

    /** bindToken TTL（旧系统 5 分钟）。 */
    private int bindTtlMinutes = 5;

    /** 码宽度。 */
    private int qrWidth = 512;

    /** 未绑定用户在登录/注册成功时自动确认其 pending 会话（默认 false=严格复刻旧系统"需二次扫码"）。 */
    private boolean autoConfirmOnBind = false;

    public boolean credentialsPresent() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /** provider=auto：凭据齐备走 real，否则 mock（本地/CI 无凭据环境全链路仍可用）。 */
    public String effectiveProvider() {
        if ("auto".equalsIgnoreCase(provider)) {
            return credentialsPresent() ? "real" : "mock";
        }
        return provider;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getQrPage() {
        return qrPage;
    }

    public void setQrPage(String qrPage) {
        this.qrPage = qrPage;
    }

    public String getEnvVersion() {
        return envVersion;
    }

    public void setEnvVersion(String envVersion) {
        this.envVersion = envVersion;
    }

    public int getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(int sessionTtlMinutes) {
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    public int getBindTtlMinutes() {
        return bindTtlMinutes;
    }

    public void setBindTtlMinutes(int bindTtlMinutes) {
        this.bindTtlMinutes = bindTtlMinutes;
    }

    public int getQrWidth() {
        return qrWidth;
    }

    public void setQrWidth(int qrWidth) {
        this.qrWidth = qrWidth;
    }

    public boolean isAutoConfirmOnBind() {
        return autoConfirmOnBind;
    }

    public void setAutoConfirmOnBind(boolean autoConfirmOnBind) {
        this.autoConfirmOnBind = autoConfirmOnBind;
    }
}
