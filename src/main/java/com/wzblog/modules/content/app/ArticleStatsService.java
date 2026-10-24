package com.wzblog.modules.content.app;

import com.wzblog.modules.content.app.port.DailyViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 日阅读回填：Lua 原子读取并删除两个 Hash，重复调用返回空（M2 §9）。
 */
@Service
public class ArticleStatsService {

    private static final Logger log = LoggerFactory.getLogger(ArticleStatsService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DefaultRedisScript<List> DRAIN_SCRIPT = new DefaultRedisScript<>("""
            local articleViews = redis.call('HGETALL', KEYS[1])
            local sources = redis.call('HGETALL', KEYS[2])
            redis.call('DEL', KEYS[1], KEYS[2])
            local out = {}
            for i = 1, #articleViews do out[#out + 1] = articleViews[i] end
            out[#out + 1] = '@'
            for i = 1, #sources do out[#out + 1] = sources[i] end
            return out
            """, List.class);

    private static final String SEPARATOR = "@";

    private final StringRedisTemplate redis;

    public ArticleStatsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public DailyViews drainDailyViews(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(BUSINESS_ZONE) : date;
        String suffix = DAY.format(day);
        String articleKey = "content:views:" + suffix;
        String sourceKey = "content:views:source:" + suffix;
        try {
            List<?> result = redis.execute(DRAIN_SCRIPT, List.of(articleKey, sourceKey));
            if (result == null || result.isEmpty()) {
                return new DailyViews(Map.of(), 0, 0);
            }
            int separator = result.indexOf(SEPARATOR);
            if (separator < 0) {
                return new DailyViews(Map.of(), 0, 0);
            }
            Map<Long, Integer> articleViews = new LinkedHashMap<>();
            for (int i = 0; i + 1 < separator; i += 2) {
                articleViews.put(Long.parseLong(String.valueOf(result.get(i))),
                        Integer.parseInt(String.valueOf(result.get(i + 1))));
            }
            int internal = 0;
            int external = 0;
            for (int i = separator + 1; i + 1 < result.size(); i += 2) {
                String field = String.valueOf(result.get(i));
                int value = Integer.parseInt(String.valueOf(result.get(i + 1)));
                if ("internal".equals(field)) {
                    internal = value;
                } else if ("external".equals(field)) {
                    external = value;
                }
            }
            return new DailyViews(articleViews, internal, external);
        } catch (DataAccessException ex) {
            log.warn("日阅读计数读取失败，按空结果返回, date={}", suffix, ex);
            return new DailyViews(Map.of(), 0, 0);
        }
    }
}
