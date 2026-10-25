package com.wzblog.modules.admin.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.admin.domain.SiteConfig;
import com.wzblog.modules.admin.infra.mapper.SiteConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 站点配置只读服务（M3/M6 等消费）：进程内缓存 60s，避免每条评论都打配置表。
 * <p>公开写入路由由 M7 提供；本服务只负责读取与类型转换。</p>
 */
@Service
public class SiteConfigService {

    private static final Logger log = LoggerFactory.getLogger(SiteConfigService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final SiteConfigMapper siteConfigMapper;
    private final ObjectMapper objectMapper;

    private volatile Map<String, SiteConfig> cache = Map.of();
    private volatile Instant cacheAt = Instant.EPOCH;

    public SiteConfigService(SiteConfigMapper siteConfigMapper, ObjectMapper objectMapper) {
        this.siteConfigMapper = siteConfigMapper;
        this.objectMapper = objectMapper;
    }

    public String getString(String key, String defaultValue) {
        SiteConfig config = get(key);
        if (config == null || config.getConfigValue() == null) {
            return defaultValue;
        }
        String value = config.getConfigValue().trim();
        // JSON 列存字符串会带引号
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public List<String> getStringList(String key) {
        SiteConfig config = get(key);
        if (config == null || config.getConfigValue() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(config.getConfigValue(), new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("配置 {} 不是合法 JSON 数组: {}", key, config.getConfigValue());
            return List.of();
        }
    }

    private SiteConfig get(String key) {
        return cached().get(key);
    }

    private Map<String, SiteConfig> cached() {
        Instant now = Instant.now();
        Map<String, SiteConfig> local = cache;
        if (now.isBefore(cacheAt.plus(CACHE_TTL)) && !local.isEmpty()) {
            return local;
        }
        synchronized (this) {
            if (now.isBefore(cacheAt.plus(CACHE_TTL)) && !cache.isEmpty()) {
                return cache;
            }
            Map<String, SiteConfig> fresh = new ConcurrentHashMap<>();
            for (SiteConfig config : siteConfigMapper.selectAll()) {
                fresh.put(config.getConfigKey(), config);
            }
            cache = Map.copyOf(fresh);
            cacheAt = now;
            return cache;
        }
    }

    /** M7 修改配置后调用，立即失效缓存。 */
    public void invalidate() {
        synchronized (this) {
            cacheAt = Instant.EPOCH;
        }
    }

    /** 测试与内部使用：直接覆盖缓存。 */
    void overrideForTest(Map<String, SiteConfig> values) {
        synchronized (this) {
            cache = Map.copyOf(values);
            cacheAt = Instant.now().plus(Duration.ofHours(1));
        }
    }
}
