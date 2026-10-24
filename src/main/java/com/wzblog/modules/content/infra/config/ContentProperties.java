package com.wzblog.modules.content.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 内容模块配置，集中在 {@code wzblog.content.*}（00 §7）。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.content")
public class ContentProperties {

    /** 正文 UTF-8 上限，默认 200 KB。 */
    private int maxContentBytes = 204800;

    /** 每篇保留的修订版本数。 */
    private int revisionKeep = 20;

    /** 详情缓存 TTL（±10% 抖动）。 */
    private Duration detailCacheTtl = Duration.ofMinutes(10);

    /** 热门/归档短缓存 TTL。 */
    private Duration shortCacheTtl = Duration.ofMinutes(5);

    /** 阅读去重 key TTL。 */
    private Duration viewDedupeTtl = Duration.ofHours(25);

    /** 判定站内来源的域名白名单（Referer 主机）。 */
    private List<String> siteHosts = new ArrayList<>(List.of("localhost", "127.0.0.1"));

    public int getMaxContentBytes() {
        return maxContentBytes;
    }

    public void setMaxContentBytes(int maxContentBytes) {
        this.maxContentBytes = maxContentBytes;
    }

    public int getRevisionKeep() {
        return revisionKeep;
    }

    public void setRevisionKeep(int revisionKeep) {
        this.revisionKeep = revisionKeep;
    }

    public Duration getDetailCacheTtl() {
        return detailCacheTtl;
    }

    public void setDetailCacheTtl(Duration detailCacheTtl) {
        this.detailCacheTtl = detailCacheTtl;
    }

    public Duration getShortCacheTtl() {
        return shortCacheTtl;
    }

    public void setShortCacheTtl(Duration shortCacheTtl) {
        this.shortCacheTtl = shortCacheTtl;
    }

    public Duration getViewDedupeTtl() {
        return viewDedupeTtl;
    }

    public void setViewDedupeTtl(Duration viewDedupeTtl) {
        this.viewDedupeTtl = viewDedupeTtl;
    }

    public List<String> getSiteHosts() {
        return siteHosts;
    }

    public void setSiteHosts(List<String> siteHosts) {
        this.siteHosts = siteHosts;
    }
}