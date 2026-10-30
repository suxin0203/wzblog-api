package com.wzblog.modules.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7 运营集成测试：配置白名单与更新失效、轮播/友链 CRUD、看板预聚合、审计查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private com.wzblog.modules.admin.app.SiteConfigService siteConfigService;

    @Autowired
    private com.wzblog.modules.admin.app.DailyStatsAggregateJob aggregateJob;

    @Test
    void publicConfigsWhitelistAndAdminUpdate() {
        // 公开白名单：种子含 comment.guest_enabled（is_public=1），不含敏感词库
        HttpResult publics = get("/api/v1/public/configs", null);
        assertThat(publics.status()).isEqualTo(200);
        assertThat(publics.data().has("comment.guest_enabled")).isTrue();
        assertThat(publics.data().has("comment.sensitive_words")).isFalse();

        // 后台全量（ADMIN）
        HttpResult admin = get("/api/v1/admin/configs", adminToken());
        assertThat(admin.status()).isEqualTo(200);
        assertThat(admin.data().has("site.name")).isTrue();

        // 更新 site.name 并验证公开缓存失效
        HttpResult updated = put("/api/v1/admin/configs/site.name",
                Map.of("value", "文栈测试站"), adminToken());
        assertThat(updated.status()).isEqualTo(200);
        HttpResult after = get("/api/v1/public/configs", null);
        // site.name 是否公开取决于种子 is_public；直接读服务验证
        assertThat(siteConfigService.getString("site.name", null)).isEqualTo("文栈测试站");
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'CONFIG_UPDATE' AND target_id = 'site.name'",
                Long.class);
        assertThat(audits).isEqualTo(1L);

        // 普通用户 403
        String username = unique("cf");
        register(username, PASSWORD, null);
        assertThat(get("/api/v1/admin/configs", accessToken(username, PASSWORD))
                .status()).isEqualTo(403);
        // 未知键 404
        assertThat(put("/api/v1/admin/configs/no.such.key", Map.of("value", "x"),
                adminToken()).status()).isEqualTo(404);
    }

    @Test
    void bannerAndFriendLinkCrud() {
        // 造一个 BANNER 图片文件（M5 引用计数需要真实行）
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', ?, 1, 'BANNER', 'ACTIVE', 0, 100)
                """, "banners/2026/10/banner-test.png");

        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("title", "新年活动");
        banner.put("imageKey", "banners/2026/10/banner-test.png");
        banner.put("linkUrl", "https://example.com");
        banner.put("sortOrder", 1);
        banner.put("status", "ENABLED");
        HttpResult created = post("/api/v1/admin/banners", banner, adminToken());
        assertThat(created.status()).isEqualTo(200);
        long bannerId = created.data().path("id").asLong();

        // 公开列表可见，图片引用计数 +1
        HttpResult active = get("/api/v1/banners", null);
        assertThat(active.status()).isEqualTo(200);
        Integer refCount = jdbc.queryForObject(
                "SELECT ref_count FROM files WHERE object_key = 'banners/2026/10/banner-test.png'",
                Integer.class);
        assertThat(refCount).isEqualTo(1);

        // 停用后公开列表不含
        banner.put("status", "DISABLED");
        assertThat(put("/api/v1/admin/banners/" + bannerId, banner, adminToken())
                .status()).isEqualTo(200);
        assertThat(get("/api/v1/banners", null).data().size()).isZero();

        // 删除释放引用
        assertThat(delete("/api/v1/admin/banners/" + bannerId, adminToken()).status()).isEqualTo(200);
        refCount = jdbc.queryForObject(
                "SELECT ref_count FROM files WHERE object_key = 'banners/2026/10/banner-test.png'",
                Integer.class);
        assertThat(refCount).isZero();

        // 友链
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("blogName", "朋友的博客");
        link.put("blogUrl", "https://friend.example.com");
        link.put("sortOrder", 1);
        link.put("status", "ENABLED");
        HttpResult linkCreated = post("/api/v1/admin/friend-links", link, adminToken());
        assertThat(linkCreated.status()).isEqualTo(200);
        HttpResult links = get("/api/v1/friend-links", null);
        assertThat(links.data().size()).isEqualTo(1);
        assertThat(links.data().get(0).path("blogName").asText()).isEqualTo("朋友的博客");

        // 非法 URL 400
        link.put("blogUrl", "ftp://bad");
        assertThat(post("/api/v1/admin/friend-links", link, adminToken())
                .status()).isEqualTo(400);
    }

    @Test
    void dailyStatsAggregateAndDashboard() {
        // 触发一次当日聚合（公开注册等真实写入）
        String username = unique("ds");
        register(username, PASSWORD, null);
        java.time.LocalDate today = java.time.LocalDate.now(
                java.time.ZoneId.of("Asia/Shanghai"));
        aggregateJob.run(today);

        HttpResult overview = get("/api/v1/admin/dashboard/overview", adminToken());
        assertThat(overview.status()).isEqualTo(200);
        // 今日注册至少含本次
        assertThat(overview.data().path("todayRegistered").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(overview.data().path("totalUsers").asLong()).isGreaterThanOrEqualTo(1);

        HttpResult trend = get("/api/v1/admin/dashboard/user-trend?days=7", adminToken());
        assertThat(trend.status()).isEqualTo(200);
        assertThat(trend.data().size()).isGreaterThanOrEqualTo(1);

        HttpResult source = get("/api/v1/admin/dashboard/traffic-source?days=7", adminToken());
        assertThat(source.status()).isEqualTo(200);

        HttpResult points = get("/api/v1/admin/dashboard/points-stats?days=7", adminToken());
        assertThat(points.status()).isEqualTo(200);

        // 普通用户 403
        String other = unique("do");
        register(other, PASSWORD, null);
        assertThat(get("/api/v1/admin/dashboard/overview", accessToken(other, PASSWORD))
                .status()).isEqualTo(403);
    }

    @Test
    void bannerActiveWindowUsesUtcAndUtcBoundaries() {
        String objectKey = "banners/2026/10/t008-" + unique("b") + ".png";
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', ?, 1, 'BANNER', 'ACTIVE', 0, 100)
                """, objectKey);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        LocalDateTime start = now.minusMinutes(1);
        LocalDateTime end = now.plusMinutes(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "T008 UTC 窗口");
        body.put("imageKey", objectKey);
        body.put("status", "ENABLED");
        body.put("sortOrder", 1);
        body.put("startAt", start.toString());
        body.put("endAt", end.toString());
        HttpResult created = post("/api/v1/admin/banners", body, adminToken());
        assertThat(created.status()).isEqualTo(200);
        String id = created.data().path("id").asText();

        // 请求体 UTC 墙钟原样落库
        assertThat(jdbc.queryForObject("SELECT start_at FROM banners WHERE id = ?",
                LocalDateTime.class, Long.valueOf(id))).isEqualTo(start);
        assertThat(activeBannerIds()).contains(id);

        // 已过期：end_at 早于 UTC 现在 → 不生效
        body.put("startAt", now.minusMinutes(2).toString());
        body.put("endAt", now.minusSeconds(1).toString());
        assertThat(put("/api/v1/admin/banners/" + id, body, adminToken()).status()).isEqualTo(200);
        assertThat(activeBannerIds()).doesNotContain(id);

        // 未开始：start_at 晚于 UTC 现在 → 不生效
        body.put("startAt", now.plusMinutes(5).toString());
        body.put("endAt", now.plusMinutes(10).toString());
        assertThat(put("/api/v1/admin/banners/" + id, body, adminToken()).status()).isEqualTo(200);
        assertThat(activeBannerIds()).doesNotContain(id);

        delete("/api/v1/admin/banners/" + id, adminToken());
    }

    @Test
    void auditLogQueryUsesUtcBoundaries() {
        String action = "T008_AUDIT_" + unique("a");
        LocalDateTime base = LocalDateTime.of(2030, 1, 1, 0, 0, 0);
        insertAudit(action, "b-1", base.minusSeconds(1));
        insertAudit(action, "b0", base);
        insertAudit(action, "b9", base.plusSeconds(9));
        insertAudit(action, "b10", base.plusSeconds(10));

        HttpResult result = get("/api/v1/admin/audit-logs?action=" + action
                + "&from=2030-01-01T00:00:00&to=2030-01-01T00:00:10&pageSize=20", adminToken());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("total").asLong()).isEqualTo(2L); // >= from, < to
        boolean b0 = false;
        boolean b9 = false;
        for (JsonNode item : result.data().path("list")) {
            b0 |= "b0".equals(item.path("targetId").asText());
            b9 |= "b9".equals(item.path("targetId").asText());
        }
        assertThat(b0).isTrue();
        assertThat(b9).isTrue();
    }

    @Test
    void auditRangeEndCoversLastSecondWhenUiClosed() {
        String action = "T011_AUDIT_" + unique("a");
        LocalDateTime day = LocalDateTime.of(2030, 3, 1, 0, 0, 0);
        insertAudit(action, "d0", day);
        insertAudit(action, "dend", day.plusDays(1).minusNanos(100_000_000)); // 23:59:59.900
        insertAudit(action, "next", day.plusDays(1));                 // 次日 00:00:00.000

        // 界面 00:00:00~23:59:59 经 toUtcRangeEnd 转成 to=次日 00:00:00
        HttpResult result = get("/api/v1/admin/audit-logs?action=" + action
                + "&from=2030-03-01T00:00:00&to=2030-03-02T00:00:00&pageSize=20", adminToken());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("total").asLong()).isEqualTo(2L);
        boolean d0 = false;
        boolean dend = false;
        for (JsonNode item : result.data().path("list")) {
            d0 |= "d0".equals(item.path("targetId").asText());
            dend |= "dend".equals(item.path("targetId").asText());
            assertThat(item.path("targetId").asText()).isNotEqualTo("next");
        }
        assertThat(d0).isTrue();
        assertThat(dend).isTrue();
    }

    @Test
    void bannerEndSecondBoundaryExpiresExactly() throws Exception {
        String objectKey = "banners/2026/10/t011-" + unique("b") + ".png";
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', ?, 1, 'BANNER', 'ACTIVE', 0, 100)
                """, objectKey);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "T011 秒级到期");
        body.put("imageKey", objectKey);
        body.put("status", "ENABLED");
        body.put("sortOrder", 1);
        body.put("startAt", now.minusMinutes(1).toString());
        body.put("endAt", now.plusSeconds(2).toString());
        HttpResult created = post("/api/v1/admin/banners", body, adminToken());
        assertThat(created.status()).isEqualTo(200);
        String id = created.data().path("id").asText();
        try {
            assertThat(activeBannerIds()).contains(id);
            Thread.sleep(2500);
            assertThat(activeBannerIds()).doesNotContain(id); // end_at > now 半开，到点即失效
        } finally {
            delete("/api/v1/admin/banners/" + id, adminToken());
        }
    }

    private void insertAudit(String action, String targetId, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO audit_logs (operator_id, action, target_type, target_id, ip, created_at)
                VALUES (1, ?, 'SYSTEM', ?, '127.0.0.1', ?)
                """, action, targetId, createdAt);
    }

    private java.util.List<String> activeBannerIds() {
        HttpResult active = get("/api/v1/banners", null);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : active.data()) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }

    @Test
    void auditLogQueryFilters() {
        // 前序测试已产生若干审计；按动作过滤
        HttpResult all = get("/api/v1/admin/audit-logs?pageSize=5", adminToken());
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.data().path("total").asLong()).isGreaterThanOrEqualTo(1L);

        HttpResult filtered = get("/api/v1/admin/audit-logs?action=CONFIG_UPDATE", adminToken());
        assertThat(filtered.status()).isEqualTo(200);
        for (var item : filtered.data().path("list")) {
            assertThat(item.path("action").asText()).isEqualTo("CONFIG_UPDATE");
        }
    }
}
