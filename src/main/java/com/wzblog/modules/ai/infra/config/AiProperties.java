package com.wzblog.modules.ai.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M6 AI 配置（wzblog.ai.*）。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.ai")
public class AiProperties {

    /** 总开关：false 时对话/摘要返回 503 AI_DISABLED。 */
    private boolean enabled = true;

    /** 上游实现：mock（本地确定性应答）| openai（OpenAI 兼容端点）。 */
    private String provider = "mock";

    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 只从环境变量注入，不落库不写日志。 */
    private String apiKey;

    private String model = "glm-4-flash-250414";

    private int maxTokens = 800;

    private int ipMinuteQuota = 10;
    private int dailyUserQuota = 100;
    private int guestDailyQuota = 50;
    private int maxConcurrent = 200;
    private int ipConcurrent = 3;
    private int guestSessionHours = 24;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getIpMinuteQuota() {
        return ipMinuteQuota;
    }

    public void setIpMinuteQuota(int ipMinuteQuota) {
        this.ipMinuteQuota = ipMinuteQuota;
    }

    public int getDailyUserQuota() {
        return dailyUserQuota;
    }

    public void setDailyUserQuota(int dailyUserQuota) {
        this.dailyUserQuota = dailyUserQuota;
    }

    public int getGuestDailyQuota() {
        return guestDailyQuota;
    }

    public void setGuestDailyQuota(int guestDailyQuota) {
        this.guestDailyQuota = guestDailyQuota;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getIpConcurrent() {
        return ipConcurrent;
    }

    public void setIpConcurrent(int ipConcurrent) {
        this.ipConcurrent = ipConcurrent;
    }

    public int getGuestSessionHours() {
        return guestSessionHours;
    }

    public void setGuestSessionHours(int guestSessionHours) {
        this.guestSessionHours = guestSessionHours;
    }
}
