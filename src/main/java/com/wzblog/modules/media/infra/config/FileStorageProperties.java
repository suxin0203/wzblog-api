package com.wzblog.modules.media.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * M5 媒体模块配置，集中在 {@code wzblog.file.*}。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.file")
public class FileStorageProperties {

    /** 存储实现：local（本地磁盘，默认）| minio（预留）。 */
    private String storage = "local";

    /** 本地磁盘根目录。 */
    private String root = "./data/uploads";

    /** 公开访问前缀（本地模式即本服务的 /media 路由）。 */
    private String publicBaseUrl = "http://localhost:8021/media";

    /** 直传地址的基础前缀（本地模式的模拟预签名 PUT 挂在 /media-upload）。 */
    private String uploadBaseUrl = "http://localhost:8021";

    /** 能力 URL（模拟预签名/短期访问）的 HMAC 密钥。 */
    private String urlSecret;

    /** 模拟预签名 PUT 的有效期。 */
    private Duration presignTtl = Duration.ofMinutes(5);

    /** TEMP 未确认转 DELETED 的时限。 */
    private Duration confirmTimeout = Duration.ofHours(24);

    /** 单文件字节上限（5MB）。 */
    private long maxSizeBytes = 5_242_880L;

    /** 单边像素上限。 */
    private int maxSide = 8000;

    /** 总像素上限。 */
    private long maxPixels = 64_000_000L;

    /** 缩略图最长边。 */
    private int thumbLongest = 320;

    /** 缩略图 JPEG 质量。 */
    private double thumbQuality = 0.82;

    /** ACTIVE ref=0 转 ORPHAN 的宽限。 */
    private Duration orphanGrace = Duration.ofDays(7);

    /** 清理任务 cron（Asia/Shanghai）。 */
    private String cleanupCron = "0 0 3 * * *";

    /** 每用户每小时 presign 次数上限。 */
    private int presignLimitPerHour = 30;

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getUploadBaseUrl() {
        return uploadBaseUrl;
    }

    public void setUploadBaseUrl(String uploadBaseUrl) {
        this.uploadBaseUrl = uploadBaseUrl;
    }

    public String getUrlSecret() {
        return urlSecret;
    }

    public void setUrlSecret(String urlSecret) {
        this.urlSecret = urlSecret;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public int getMaxSide() {
        return maxSide;
    }

    public void setMaxSide(int maxSide) {
        this.maxSide = maxSide;
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    public int getThumbLongest() {
        return thumbLongest;
    }

    public void setThumbLongest(int thumbLongest) {
        this.thumbLongest = thumbLongest;
    }

    public double getThumbQuality() {
        return thumbQuality;
    }

    public void setThumbQuality(double thumbQuality) {
        this.thumbQuality = thumbQuality;
    }

    public Duration getOrphanGrace() {
        return orphanGrace;
    }

    public void setOrphanGrace(Duration orphanGrace) {
        this.orphanGrace = orphanGrace;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getPresignLimitPerHour() {
        return presignLimitPerHour;
    }

    public void setPresignLimitPerHour(int presignLimitPerHour) {
        this.presignLimitPerHour = presignLimitPerHour;
    }
}
