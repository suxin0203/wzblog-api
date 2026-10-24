package com.wzblog.modules.content.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.vo.ViewResultVO;
import com.wzblog.modules.content.domain.Article;
import com.wzblog.modules.content.domain.ArticleStatus;
import com.wzblog.modules.content.domain.ArticleVisibility;
import com.wzblog.modules.content.infra.config.ContentProperties;
import com.wzblog.modules.content.infra.mapper.ArticleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 阅读计数：Redis 去重 + 累计列 + 日计数器（M2 §9）。
 */
@Service
public class ArticleViewService {

    private static final Logger log = LoggerFactory.getLogger(ArticleViewService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration DAY_TTL = Duration.ofHours(25);

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redis;
    private final ContentProperties properties;

    public ArticleViewService(ArticleMapper articleMapper,
                              StringRedisTemplate redis,
                              ContentProperties properties) {
        this.articleMapper = articleMapper;
        this.redis = redis;
        this.properties = properties;
    }

    @Transactional
    public ViewResultVO record(Long articleId, CurrentUser currentUser, String source,
                               ClientInfo client, String referer) {
        Article article = articleMapper.selectAnyById(articleId);
        if (article == null || article.isDeleted() || article.getStatus() != ArticleStatus.PUBLISHED
                || article.getVisibility() == ArticleVisibility.PRIVATE) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        String dedupeKey = "content:view:" + articleId + ":" + DAY.format(today) + ":" + visitor(currentUser, client);
        boolean first = acquireDedupe(dedupeKey);
        if (!first) {
            return new ViewResultVO(false, nz(article.getViewCount()));
        }

        boolean internal = isInternal(source, referer);
        int updated = internal
                ? articleMapper.incrementViewInternal(articleId)
                : articleMapper.incrementViewExternal(articleId);
        if (updated == 0) {
            releaseDedupe(dedupeKey);
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        Article refreshed = articleMapper.selectAnyById(articleId);
        recordDaily(articleId, internal, today);
        return new ViewResultVO(true, nz(refreshed == null ? null : refreshed.getViewCount()));
    }

    private boolean acquireDedupe(String key) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", properties.getViewDedupeTtl());
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException ex) {
            log.warn("阅读去重 Redis 不可用，降级为直接计数", ex);
            return true;
        }
    }

    private void releaseDedupe(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException ex) {
            log.warn("阅读去重键回滚失败, key={}", key, ex);
        }
    }

    private void recordDaily(Long articleId, boolean internal, LocalDate day) {
        String suffix = DAY.format(day);
        String articleKey = "content:views:" + suffix;
        String sourceKey = "content:views:source:" + suffix;
        try {
            redis.opsForHash().increment(articleKey, String.valueOf(articleId), 1);
            redis.opsForHash().increment(sourceKey, internal ? "internal" : "external", 1);
            redis.expire(articleKey, DAY_TTL);
            redis.expire(sourceKey, DAY_TTL);
        } catch (DataAccessException ex) {
            log.warn("日阅读计数 Redis 不可用，已跳过, articleId={}", articleId, ex);
        }
    }

    private boolean isInternal(String source, String referer) {
        if (source != null && !source.isBlank()) {
            return "internal".equals(source.trim().toLowerCase(Locale.ROOT));
        }
        if (referer == null || referer.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(referer.trim()).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return properties.getSiteHosts().stream()
                    .filter(value -> value != null)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::equals);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String visitor(CurrentUser currentUser, ClientInfo client) {
        if (currentUser != null) {
            return "u" + currentUser.id();
        }
        String ip = client == null || client.ip() == null ? "" : client.ip();
        String ua = client == null || client.userAgent() == null ? "" : client.userAgent();
        return "h" + sha256(ip + "|" + ua).substring(0, 16);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
