package com.wzblog.modules.points;

import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 积分集成测试：事件发放幂等/日上限、并发抢库存、下单幂等、状态机、退款回补、对账。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PointsIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private com.wzblog.modules.points.app.PointsReconcileJob reconcileJob;

    // ------------------------------------------------------------ 工具

    private HttpResult adjust(String token, long userId, int amount, String reason) {
        return post("/api/v1/admin/users/" + userId + "/points",
                Map.of("amount", amount, "reason", reason), token);
    }

    private HttpResult createGoods(String token, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "test-good-" + unique("g"));
        body.put("type", "PHYSICAL");
        body.put("pointsPrice", 10);
        body.put("stock", 5);
        body.put("sortOrder", 0);
        body.putAll(overrides);
        return post("/api/v1/admin/points/goods", body, token);
    }

    private HttpResult order(String token, String idemKey, Map<String, Object> body) {
        return exchangeWithHeader("POST", "/api/v1/points/orders", body, token, idemKey);
    }

    private HttpResult exchangeWithHeader(String method, String path, Object body, String token,
                                          String idemKey) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        org.springframework.http.ResponseEntity<String> response = rest.exchange(path,
                org.springframework.http.HttpMethod.valueOf(method),
                new org.springframework.http.HttpEntity<>(body, headers), String.class);
        com.fasterxml.jackson.databind.JsonNode node = null;
        try {
            if (response.getBody() != null) {
                node = objectMapper.readTree(response.getBody());
            }
        } catch (Exception ignored) {
        }
        return new HttpResult(response.getStatusCode().value(), node, response.getHeaders());
    }

    private int balance(long userId) {
        var list = jdbc.query("SELECT balance FROM points_accounts WHERE user_id = ?",
                (rs, rowNum) -> rs.getInt(1), userId);
        return list.isEmpty() ? 0 : list.get(0);
    }

    // ------------------------------------------------------------ 账户与调分

    @Test
    void accountViewWithLazyCreationFromDailyLogin() {
        String username = unique("pz");
        register(username, PASSWORD, null);
        HttpResult account = get("/api/v1/points/account", accessToken(username, PASSWORD));
        assertThat(account.status()).isEqualTo(200);
        // 每日首次登录即发放 +5（M4 §6.1），账户行由事件懒创建
        awaitBalance(userId(username), 5);
        account = get("/api/v1/points/account", accessToken(username, PASSWORD));
        assertThat(account.data().path("balance").asInt()).isEqualTo(5);
        assertThat(account.data().path("titles").size()).isZero();
        assertThat(account.data().path("activeTitle").isNull()).isTrue();
        // 余额 = 流水合计
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM points_ledger WHERE user_id = ?",
                Long.class, userId(username));
        assertThat(sum).isEqualTo(5L);
    }

    @Test
    void adminAdjustWritesLedgerAuditAndStaysConsistent() {
        String username = unique("pa");
        register(username, PASSWORD, null);
        long userId = userId(username);

        HttpResult added = adjust(adminToken(), userId, 100, "测试加 100");
        assertThat(added.status()).isEqualTo(200);
        assertThat(added.data().path("amount").asInt()).isEqualTo(100);
        assertThat(added.data().path("balanceAfter").asInt()).isEqualTo(100);

        HttpResult invalid = adjust(adminToken(), userId, 0, "零");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("POINTS_ADJUST_INVALID");

        // 余额 = 流水合计
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM points_ledger WHERE user_id = ?",
                Long.class, userId);
        assertThat(sum).isEqualTo(100L);
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'POINTS_ADJUST' AND target_id = ?",
                Long.class, String.valueOf(userId));
        assertThat(audits).isEqualTo(1L);
    }

    // ------------------------------------------------------------ 事件发放

    @Test
    void dailyLoginEventGrantsOncePerDay() {
        String username = unique("pl");
        register(username, PASSWORD, null);
        long userId = userId(username);
        org.springframework.context.ApplicationEventPublisher publisher = eventPublisher();
        java.time.Instant now = java.time.Instant.now();
        for (int i = 0; i < 5; i++) {
            publisher.publishEvent(new com.wzblog.modules.identity.domain.event.UserLoggedInEvent(
                    userId, now, "127.0.0.1", true));
        }
        awaitBalance(userId, 5);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND type = 'DAILY_LOGIN'",
                Long.class, userId);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void articlePublishDailyCapEnforced() {
        String author = unique("pc");
        register(author, PASSWORD, null);
        long userId = userId(author);
        org.springframework.context.ApplicationEventPublisher publisher = eventPublisher();
        // 发 12 篇 → 上限 100 → 只有 10 篇计分
        for (int i = 0; i < 12; i++) {
            publisher.publishEvent(new com.wzblog.modules.content.domain.event.ArticlePublishedEvent(
                    900_000L + i, userId, "t" + i, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        }
        awaitBalance(userId, 100);
        assertThat(balance(userId)).isEqualTo(100);
    }

    @Test
    void likeGrantAndExactReversal() {
        String author = unique("pk");
        register(author, PASSWORD, null);
        long authorId = userId(author);
        long actorId = 1L;
        org.springframework.context.ApplicationEventPublisher publisher = eventPublisher();
        var toggled = new com.wzblog.modules.interaction.domain.event.ReactionToggledEvent(
                910_001L, authorId, actorId,
                com.wzblog.modules.interaction.domain.ReactionType.LIKE, true);
        publisher.publishEvent(toggled);
        awaitBalance(authorId, 1);

        // 取消点赞 → 精确反转 -1
        publisher.publishEvent(new com.wzblog.modules.interaction.domain.event.ReactionToggledEvent(
                910_001L, authorId, actorId,
                com.wzblog.modules.interaction.domain.ReactionType.LIKE, false));
        awaitBalance(authorId, 0);
        assertThat(balance(authorId)).isZero();
    }

    @Test
    void articleDeleteReversalClampsAtZero() {
        String author = unique("pd");
        register(author, PASSWORD, null);
        long authorId = userId(author);
        org.springframework.context.ApplicationEventPublisher publisher = eventPublisher();
        publisher.publishEvent(new com.wzblog.modules.content.domain.event.ArticlePublishedEvent(
                920_001L, authorId, "t", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        awaitBalance(authorId, 10);
        // 先消费 8 分
        jdbc.update("UPDATE points_accounts SET balance = 2 WHERE user_id = ?", authorId);

        publisher.publishEvent(new com.wzblog.modules.content.domain.event.ArticleDeletedEvent(
                920_001L, authorId));
        awaitLedgerType(authorId, "ARTICLE_PUBLISH_REVERSAL", 1);
        assertThat(balance(authorId)).isZero(); // 不产生负余额

        String remark = jdbc.queryForObject("""
                SELECT remark FROM points_ledger
                WHERE user_id = ? AND type = 'ARTICLE_PUBLISH_REVERSAL'
                """, String.class, authorId);
        assertThat(remark).contains("少扣 8");
    }

    private void awaitBalance(long userId, int expected) {
        for (int i = 0; i < 50; i++) {
            if (balance(userId) == expected) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitLedgerType(long userId, String type, int expectedRows) {
        for (int i = 0; i < 50; i++) {
            Long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND type = ?",
                    Long.class, userId, type);
            if (rows != null && rows >= expectedRows) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Autowired
    private org.springframework.context.ApplicationEventPublisher injectedPublisher;

    private org.springframework.context.ApplicationEventPublisher eventPublisher() {
        return injectedPublisher;
    }

    // ------------------------------------------------------------ 下单与并发

    @Test
    void concurrentOrdersRaceSingleStockItem() throws Exception {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 1, "pointsPrice", 10));
        assertThat(goods.status()).isEqualTo(200);
        long goodsId = goods.data().path("id").asLong();

        String username = unique("po");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 500, "备货");
        awaitBalance(userId, 505); // 500 调分 + 5 每日登录
        String token = accessToken(username, PASSWORD);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        Map<String, Object> body = Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverAddress", "北京市海淀区");
        for (int i = 0; i < threads; i++) {
            int seq = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                HttpResult result = order(token, "race-key-" + seq, body);
                if ("OK".equals(result.code()) && result.status() == 200) {
                    created.incrementAndGet();
                } else if ("GOODS_STOCK_INSUFFICIENT".equals(result.code())) {
                    insufficient.incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(created.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(threads - 1);
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM points_goods WHERE id = ?", Integer.class, goodsId);
        assertThat(stock).isZero();
        Integer sold = jdbc.queryForObject(
                "SELECT sold_count FROM points_goods WHERE id = ?", Integer.class, goodsId);
        assertThat(sold).isEqualTo(1);
        // 只扣一次分
        assertThat(balance(userId)).isEqualTo(495);
    }

    @Test
    void idempotentOrderCreationReturnsSameOrder() {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 3, "pointsPrice", 10));
        long goodsId = goods.data().path("id").asLong();
        String username = unique("pi");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 200, "备货");
        awaitBalance(userId, 200);
        String token = accessToken(username, PASSWORD);
        Map<String, Object> body = Map.of("goodsId", goodsId, "quantity", 2,
                "receiverName", "李四", "receiverPhone", "13900139000",
                "receiverAddress", "上海市");
        String key = "idem-" + unique("k");
        awaitBalance(userId, 205); // 200 调分 + 5 每日登录

        HttpResult first = order(token, key, body);
        assertThat(first.status()).isEqualTo(200);
        String orderNo = first.data().path("orderNo").asText();
        assertThat(orderNo).matches("^PO\\d{14}[A-Z0-9]{8}$").hasSize(24);

        for (int i = 0; i < 5; i++) {
            HttpResult retry = order(token, key, body);
            assertThat(retry.status()).isEqualTo(200);
            assertThat(retry.data().path("orderNo").asText()).isEqualTo(orderNo);
        }
        // 只扣一次分、一条流水、一个订单
        assertThat(balance(userId)).isEqualTo(185);
        Long redeems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND type = 'REDEEM'",
                Long.class, userId);
        assertThat(redeems).isEqualTo(1L);
        Long orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_orders WHERE user_id = ?", Long.class, userId);
        assertThat(orders).isEqualTo(1L);

        // 同键不同请求体 → 409
        Map<String, Object> different = Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "李四", "receiverPhone", "13900139000",
                "receiverAddress", "上海市");
        HttpResult conflict = order(token, key, different);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("COMMON_IDEMPOTENCY_CONFLICT");

        // 缺幂等键 → 400
        HttpResult noKey = order(token, null, body);
        assertThat(noKey.status()).isEqualTo(400);

        // 余额不足 → 422 无副作用（高价商品，库存充足）
        HttpResult expensive = createGoods(adminToken(),
                Map.of("pointsPrice", 100, "stock", 5));
        HttpResult poor = order(token, "poor-" + unique("k"),
                Map.of("goodsId", expensive.data().path("id").asLong(), "quantity", 5,
                        "receiverName", "王五", "receiverPhone", "13700137000",
                        "receiverAddress", "广州"));
        assertThat(poor.status()).isEqualTo(422);
        assertThat(poor.code()).isEqualTo("POINTS_INSUFFICIENT");
    }

    @Test
    void orderStateMachineAndRefund() {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 2, "pointsPrice", 10));
        long goodsId = goods.data().path("id").asLong();
        String username = unique("ps");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 100, "备货");
        awaitBalance(userId, 100);
        String token = accessToken(username, PASSWORD);
        Map<String, Object> body = Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "赵六", "receiverPhone", "13600136000", "receiverAddress", "深圳");
        HttpResult created = order(token, "sm-" + unique("k"), body);
        String orderNo = created.data().path("orderNo").asText();

        // 非法流转：PENDING → SHIPPED
        HttpResult bad = post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "SHIPPED"), adminToken());
        assertThat(bad.status()).isEqualTo(409);
        assertThat(bad.code()).isEqualTo("ORDER_STATE_INVALID");

        // 通过 → 发货（缺物流 422）→ 补物流 → 完成
        assertThat(post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "APPROVED"), adminToken()).status()).isEqualTo(200);
        HttpResult noLogistics = post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "SHIPPED"), adminToken());
        assertThat(noLogistics.status()).isEqualTo(422);
        assertThat(noLogistics.code()).isEqualTo("ORDER_LOGISTICS_REQUIRED");

        assertThat(post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "SHIPPED", "logisticsCompany", "顺丰",
                        "logisticsNo", "SF123456"), adminToken()).status()).isEqualTo(200);
        assertThat(post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "COMPLETED"), adminToken()).status()).isEqualTo(200);

        HttpResult detail = get("/api/v1/points/orders/" + orderNo, token);
        assertThat(detail.data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.data().path("logs").size()).isEqualTo(4);
        assertThat(detail.data().path("receiverPhone").asText()).isEqualTo("136****6000");
        assertThat(detail.data().path("receiverPhonePlain").asText()).isEqualTo("13600136000");

        // 越权 404
        String other = unique("so");
        register(other, PASSWORD, null);
        HttpResult forbidden = get("/api/v1/points/orders/" + orderNo,
                accessToken(other, PASSWORD));
        assertThat(forbidden.status()).isEqualTo(404);
    }

    @Test
    void cancelRefundsPointsAndRestoresStock() {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 2, "pointsPrice", 10));
        long goodsId = goods.data().path("id").asLong();
        String username = unique("pc2");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 100, "备货");
        awaitBalance(userId, 100);
        String token = accessToken(username, PASSWORD);
        HttpResult created = order(token, "cx-" + unique("k"),
                Map.of("goodsId", goodsId, "quantity", 1, "receiverName", "钱七",
                        "receiverPhone", "13500135000", "receiverAddress", "杭州"));
        String orderNo = created.data().path("orderNo").asText();
        awaitBalance(userId, 95); // 100 调分 + 5 登录 - 10 下单

        HttpResult cancelled = post("/api/v1/points/orders/" + orderNo + "/cancel", Map.of(),
                token);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.data().path("status").asText()).isEqualTo("CANCELLED");
        // 用户取消不得写 admin_remark（回归：曾把 cancelled_reason 误写入 admin_remark）
        String adminRemark = jdbc.queryForObject(
                "SELECT admin_remark FROM points_orders WHERE order_no = ?", String.class, orderNo);
        assertThat(adminRemark).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT cancelled_reason FROM points_orders WHERE order_no = ?",
                String.class, orderNo)).isEqualTo("用户取消");
        awaitBalance(userId, 105); // 退款 +10
        Integer stock = jdbc.queryForObject("SELECT stock FROM points_goods WHERE id = ?",
                Integer.class, goodsId);
        assertThat(stock).isEqualTo(2);

        // 再次取消幂等，不重复退款
        HttpResult again = post("/api/v1/points/orders/" + orderNo + "/cancel", Map.of(), token);
        assertThat(again.status()).isEqualTo(200);
        Long refunds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND type = 'REFUND'",
                Long.class, userId);
        assertThat(refunds).isEqualTo(1L);
        assertThat(balance(userId)).isEqualTo(105);
    }

    @Test
    void titleOrderGrantsTitleOnce() {
        HttpResult title = post("/api/v1/admin/titles",
                Map.of("name", "称号-" + unique("t"), "cssClass", "gold"), adminToken());
        assertThat(title.status()).isEqualTo(200);
        long titleId = title.data().path("id").asLong();

        java.util.Map<String, Object> titleGoods = new LinkedHashMap<>();
        titleGoods.put("type", "TITLE");
        titleGoods.put("titleId", titleId);
        titleGoods.put("pointsPrice", 20);
        titleGoods.put("stock", null);
        HttpResult goods = createGoods(adminToken(), titleGoods);
        long goodsId = goods.data().path("id").asLong();

        String username = unique("pt");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 100, "备货");
        awaitBalance(userId, 105); // 100 调分 + 5 每日登录
        String token = accessToken(username, PASSWORD);

        HttpResult created = order(token, "tt-" + unique("k"), Map.of("goodsId", goodsId));
        String orderNo = created.data().path("orderNo").asText();
        assertThat(post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "APPROVED"), adminToken()).status()).isEqualTo(200);

        Long owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_titles WHERE user_id = ? AND title_id = ?",
                Long.class, userId, titleId);
        assertThat(owned).isEqualTo(1L);

        // 账户视图返回称号
        HttpResult account = get("/api/v1/points/account", token);
        assertThat(account.data().path("titles").size()).isEqualTo(1);

        // 已持有再下单 → 409
        adjust(adminToken(), userId, 100, "再备货");
        awaitBalance(userId, 205);
        HttpResult again = order(token, "tt2-" + unique("k"), Map.of("goodsId", goodsId));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ORDER_TITLE_ALREADY_OWNED");
    }

    @Test
    void titleUpdateExcludesSelfAndRejectsRealConflict() {
        String name = "称号-" + unique("tu");
        HttpResult created = post("/api/v1/admin/titles",
                Map.of("name", name, "cssClass", "gold", "description", "初始", "sortOrder", 1),
                adminToken());
        assertThat(created.status()).isEqualTo(200);
        long id = created.data().path("id").asLong();

        // 不改名，只改描述/排序 → 200（查重必须排除自身）
        HttpResult sameName = put("/api/v1/admin/titles/" + id,
                Map.of("name", name, "cssClass", "gold", "description", "改描述", "sortOrder", 9),
                adminToken());
        assertThat(sameName.status()).isEqualTo(200);
        assertThat(sameName.data().path("description").asText()).isEqualTo("改描述");
        assertThat(sameName.data().path("sortOrder").asInt()).isEqualTo(9);

        // 改成另一个在用称号的名字 → 409
        String otherName = "称号-" + unique("tu");
        HttpResult other = post("/api/v1/admin/titles",
                Map.of("name", otherName, "description", "他人"), adminToken());
        assertThat(other.status()).isEqualTo(200);
        long otherId = other.data().path("id").asLong();
        HttpResult conflict = put("/api/v1/admin/titles/" + otherId,
                Map.of("name", name, "description", "撞名"), adminToken());
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("TITLE_NAME_CONFLICT");
    }

    @Test
    void softDeletedTitleNameStillConflictsOnCreate() {
        String name = "称号-" + unique("td");
        HttpResult created = post("/api/v1/admin/titles", Map.of("name", name), adminToken());
        assertThat(created.status()).isEqualTo(200);
        long id = created.data().path("id").asLong();
        assertThat(delete("/api/v1/admin/titles/" + id, adminToken()).status()).isEqualTo(200);

        // 现有语义：唯一索引包含软删行，预检查不可见，insert 冲突兜底为 409（本轮不改）
        HttpResult again = post("/api/v1/admin/titles", Map.of("name", name), adminToken());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("TITLE_NAME_CONFLICT");
    }

    @Test
    void reconcileJobFixesBalanceDrift() {
        String username = unique("pr");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 100, "对账前");
        awaitBalance(userId, 100);
        // 人为漂移
        jdbc.update("UPDATE points_accounts SET balance = 150 WHERE user_id = ?", userId);

        reconcileJob.run();
        assertThat(balance(userId)).isEqualTo(100);
        Long fixes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND remark LIKE 'RECONCILE_FIX:%'",
                Long.class, userId);
        assertThat(fixes).isEqualTo(1L);

        // 同日重跑不再追加
        reconcileJob.run();
        fixes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id = ? AND remark LIKE 'RECONCILE_FIX:%'",
                Long.class, userId);
        assertThat(fixes).isEqualTo(1L);
    }

    @Test
    void publicGoodsAndTitleListFilters() {
        createGoods(adminToken(), Map.of("status", "OFF_SHELF"));
        HttpResult onSale = createGoods(adminToken(), Map.of());
        long id = onSale.data().path("id").asLong();

        HttpResult list = get("/api/v1/points/goods", null);
        assertThat(list.status()).isEqualTo(200);
        for (var item : list.data().path("list")) {
            assertThat(item.path("id").asLong()).isNotEqualTo(id == 0 ? -1 : onSale.data().path("id").asLong() - 1);
        }
        HttpResult detail = get("/api/v1/points/goods/" + id, null);
        assertThat(detail.status()).isEqualTo(200);
        HttpResult titles = get("/api/v1/titles", null);
        assertThat(titles.status()).isEqualTo(200);
    }

    @Test
    void adminRejectWritesRealAdminRemark() {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 1, "pointsPrice", 10));
        long goodsId = goods.data().path("id").asLong();
        String username = unique("rj");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 100, "备货");
        awaitBalance(userId, 105);
        String token = accessToken(username, PASSWORD);
        HttpResult created = order(token, "rj-" + unique("k"),
                Map.of("goodsId", goodsId, "quantity", 1, "receiverName", "孙八",
                        "receiverPhone", "13400134000", "receiverAddress", "南京"));
        String orderNo = created.data().path("orderNo").asText();

        HttpResult rejected = post("/api/v1/admin/points/orders/" + orderNo + "/transition",
                Map.of("toStatus", "REJECTED", "adminRemark", "库存不足，请联系客服"),
                adminToken());
        assertThat(rejected.status()).isEqualTo(200);
        // 回归：admin_remark 必须是管理员真实备注，而不是 cancelled_reason 的副本
        var row = jdbc.queryForMap(
                "SELECT admin_remark, cancelled_reason FROM points_orders WHERE order_no = ?",
                orderNo);
        assertThat(row.get("admin_remark")).isEqualTo("库存不足，请联系客服");
        assertThat(row.get("cancelled_reason")).isEqualTo("库存不足，请联系客服");
    }

    @Test
    void sameIdempotencyKeyWithDifferentAddressRejected() {
        HttpResult goods = createGoods(adminToken(), Map.of("stock", 5, "pointsPrice", 10));
        long goodsId = goods.data().path("id").asLong();
        String username = unique("ia");
        register(username, PASSWORD, null);
        long userId = userId(username);
        adjust(adminToken(), userId, 200, "备货");
        awaitBalance(userId, 205);
        String token = accessToken(username, PASSWORD);
        String key = "ia-" + unique("k");

        HttpResult first = order(token, key, Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverAddress", "北京市海淀区"));
        assertThat(first.status()).isEqualTo(200);

        // 回归：同 key、同 goodsId、同 quantity、仅换收货地址 → 必须冲突而非静默返回旧订单
        HttpResult replay = order(token, key, Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverAddress", "上海市浦东新区"));
        assertThat(replay.status()).isEqualTo(409);
        assertThat(replay.code()).isEqualTo("COMMON_IDEMPOTENCY_CONFLICT");

        // 完全一致的请求体仍幂等返回同一订单
        HttpResult identical = order(token, key, Map.of("goodsId", goodsId, "quantity", 1,
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverAddress", "北京市海淀区"));
        assertThat(identical.status()).isEqualTo(200);
        assertThat(identical.data().path("orderNo").asText())
                .isEqualTo(first.data().path("orderNo").asText());
        Long orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_orders WHERE user_id = ?", Long.class, userId);
        assertThat(orders).isEqualTo(1L);
    }
}
