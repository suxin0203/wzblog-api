package com.wzblog.modules.admin.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.domain.SiteConfig;
import com.wzblog.modules.admin.infra.mapper.SiteConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点配置用例：公开白名单读取、后台全量/单键更新（写审计 + 失效缓存）。
 */
@Service
public class SiteConfigAppService {

    private final SiteConfigMapper siteConfigMapper;
    private final SiteConfigService siteConfigService;
    private final AuditRecorder auditRecorder;

    public SiteConfigAppService(SiteConfigMapper siteConfigMapper,
                                SiteConfigService siteConfigService,
                                AuditRecorder auditRecorder) {
        this.siteConfigMapper = siteConfigMapper;
        this.siteConfigService = siteConfigService;
        this.auditRecorder = auditRecorder;
    }

    /** GET /public/configs：只返回 is_public=1 的键值。 */
    public Map<String, String> publicMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (SiteConfig config : siteConfigMapper.selectPublic()) {
            result.put(config.getConfigKey(), stripQuotes(config.getConfigValue()));
        }
        return result;
    }

    public Map<String, Object> adminMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SiteConfig config : siteConfigMapper.selectAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", stripQuotes(config.getConfigValue()));
            item.put("valueType", config.getValueType());
            item.put("description", config.getDescription());
            item.put("isPublic", Boolean.TRUE.equals(config.getIsPublic()));
            item.put("updatedAt", Times.toInstant(config.getUpdatedAt()));
            result.put(config.getConfigKey(), item);
        }
        return result;
    }

    @Transactional
    public void update(CurrentUser admin, String key, String value) {
        SiteConfig config = siteConfigMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SiteConfig>()
                                .eq(SiteConfig::getConfigKey, key))
                .stream().findFirst().orElse(null);
        if (config == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        String before = config.getConfigValue();
        config.setConfigValue(encode(config.getValueType(), value));
        config.setUpdatedBy(admin.id());
        siteConfigMapper.updateById(config);
        auditRecorder.record(admin, "CONFIG_UPDATE", "CONFIG", key,
                quote(before), quote(value));
        siteConfigService.invalidate();
    }

    /** config_value 为 JSON 列：按 value_type 编码（STRING 加引号、BOOLEAN/NUMBER 裸值、JSON 原样）。 */
    private static String encode(String valueType, String value) {
        String type = valueType == null ? "STRING" : valueType.toUpperCase();
        return switch (type) {
            case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "1".equals(value) ? "true" : "false";
            case "NUMBER" -> value == null ? "0" : value.trim();
            case "JSON" -> value == null || value.trim().isEmpty() ? "null" : value.trim();
            default -> "\"" + (value == null ? "" : value
                    .replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
        };
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
