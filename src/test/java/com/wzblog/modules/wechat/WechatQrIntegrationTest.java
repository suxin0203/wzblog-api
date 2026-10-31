package com.wzblog.modules.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 新契约（/api/v1/auth/wechat/* 与 /api/v1/auth/qr/*）集成测试（Mock 上游）：
 * code 登录自动建号、bind 幂等/换绑拒绝、扫码会话创建/轮询（CONFIRMED 附 ticket 不消费）、
 * confirm(JWT) 下发 bindToken、exchange 一次性换票、register-and-confirm 建正式账号（source=QR）、
 * bind-and-confirm 鉴权、过期 410、限流。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WechatQrIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    /** 小程序 code 登录：返回 accessToken（Mock 同一 code 恒映射同一 openid）。 */
    private String wechatLogin(String code) {
        var response = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(response.code()).as("body=%s", response.body()).isEqualTo("OK");
        return response.data().path("accessToken").asText();
    }

    private String createSession() {
        var response = post("/api/v1/auth/qr/sessions", Map.of(), null);
        assertThat(response.code()).as("body=%s", response.body()).isEqualTo("OK");
        return response.data().path("sceneId").asText();
    }

    // ------------------------------------------------------------ /auth/wechat/login|bind

    @Test
    void wechatLoginAutoCreatesUserAndReturnsTokenPair() {
        String code = "v1-login-" + unique("c");
        var response = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(response.code()).isEqualTo("OK");
        var data = response.data();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();
        assertThat(data.path("expiresIn").asInt()).isEqualTo(900);
        var user = data.path("user");
        assertThat(user.path("openidBound").asBoolean()).isTrue();
        assertThat(user.path("role").asText()).isEqualTo("USER");
        assertThat(user.path("username").asText()).startsWith("wx_");
        assertThat(user.has("openid")).isFalse(); // openid 明文永不外泄

        // 同一 openid 再次登录：同一账号（回查走登录分支，不双账号）
        var second = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(second.code()).isEqualTo("OK");
        assertThat(second.data().path("user").path("id").asText())
                .isEqualTo(user.path("id").asText());

        // 无效 code → 400 WECHAT_CODE_INVALID
        var bad = post("/api/v1/auth/wechat/login", Map.of("code", "mock-bad-code"), null);
        assertThat(bad.code()).isEqualTo("WECHAT_CODE_INVALID");
    }

    @Test
    void wechatLoginRejectsDisabledAccount() {
        String code = "v1-dis-" + unique("c");
        var first = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(first.code()).isEqualTo("OK");
        String userId = first.data().path("user").path("id").asText();

        // 停用账号后再登录 → 403 AUTH_ACCOUNT_DISABLED，不签发 Token（M8 TC-003）
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", Long.valueOf(userId));
        var second = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(second.status()).isEqualTo(403);
        assertThat(second.code()).isEqualTo("AUTH_ACCOUNT_DISABLED");
    }

    @Test
    void wechatBindRequiresLoginAndIsIdempotent() {
        String code = "v1-bind-" + unique("c");
        String accessToken = wechatLogin(code);

        // 未登录 → 401
        var anonymous = post("/api/v1/auth/wechat/bind",
                Map.of("code", "v1-bind-anon-" + unique("c")), null);
        assertThat(anonymous.code()).isEqualTo("AUTH_UNAUTHORIZED");

        // 已绑定同 openid（同一 code → 同一 openid）再绑 → 幂等 bound=true
        var idempotent = post("/api/v1/auth/wechat/bind", Map.of("code", code), accessToken);
        assertThat(idempotent.code()).as("body=%s", idempotent.body()).isEqualTo("OK");
        assertThat(idempotent.data().path("bound").asBoolean()).isTrue();

        // 绑新 openid（该账号已有 openid）→ 409 WECHAT_ACCOUNT_ALREADY_BOUND
        var rebinding = post("/api/v1/auth/wechat/bind",
                Map.of("code", "v1-bind-new-" + unique("c")), accessToken);
        assertThat(rebinding.code()).isEqualTo("WECHAT_ACCOUNT_ALREADY_BOUND");
    }

    // ------------------------------------------------------------ /auth/qr/sessions 基础

    @Test
    void qrSessionCreatePollAndCodePng() {
        String sceneId = createSession();
        assertThat(sceneId).hasSize(16).matches("[0-9A-Za-z]{16}");

        // DB 审计行：PENDING，TTL ≈120s
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), expires_at) AS ttl "
                        + "FROM wechat_qr_sessions WHERE scene_id = ?", sceneId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        long ttl = ((Number) row.get("ttl")).longValue();
        assertThat(ttl).isBetween(100L, 120L);

        // 轮询 PENDING（v1.6.5：client 来源环境，snake_case，IP 脱敏）
        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.code()).isEqualTo("OK");
        assertThat(poll.data().path("status").asText()).isEqualTo("PENDING");
        assertThat(poll.data().path("ticket").isNull()).isTrue();
        JsonNode client = poll.data().path("client");
        assertThat(client.path("channel").asText()).isEqualTo("pc");
        assertThat(client.path("channel_label").asText()).isEqualTo("电脑网页");
        assertThat(client.path("is_mobile").asBoolean()).isFalse();
        assertThat(client.path("ip").asText()).endsWith("*.*");

        // 码 PNG：image/png；二次命中缓存
        ResponseEntity<byte[]> png = rest.getForEntity(
                "/api/v1/auth/qr/sessions/" + sceneId + "/code", byte[].class);
        assertThat(png.getStatusCode().value()).isEqualTo(200);
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(png.getBody()).isNotEmpty();
        ResponseEntity<byte[]> again = rest.getForEntity(
                "/api/v1/auth/qr/sessions/" + sceneId + "/code", byte[].class);
        assertThat(again.getBody()).isEqualTo(png.getBody());

        // 未知 scene：404 WECHAT_QR_NOT_FOUND
        assertThat(get("/api/v1/auth/qr/sessions/no-such-scene", null).code())
                .isEqualTo("WECHAT_QR_NOT_FOUND");
    }

    // ------------------------------------------------------------ 全链路：confirm(JWT) → poll → exchange

    @Test
    void qrFullChainConfirmWithJwtThenExchangeOnce() throws Exception {
        String accessToken = wechatLogin("v1-chain-" + unique("c"));
        String sceneId = createSession();

        // confirm（JWT，无 bindToken）→ CONFIRMED + 一次性 bindToken
        var confirmed = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), accessToken);
        assertThat(confirmed.code()).as("body=%s", confirmed.body()).isEqualTo("OK");
        assertThat(confirmed.data().path("status").asText()).isEqualTo("CONFIRMED");
        String bindToken = confirmed.data().path("bindToken").asText();
        assertThat(bindToken).hasSize(43);
        assertThat(confirmed.data().path("bindTokenExpiresAt").asText()).isNotBlank();

        // 重复确认 → 409 ALREADY_CONFIRMED
        var again = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), accessToken);
        assertThat(again.code()).isEqualTo("WECHAT_QR_ALREADY_CONFIRMED");

        // 轮询：CONFIRMED + ticket（不消费）
        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.data().path("status").asText()).isEqualTo("CONFIRMED");
        String ticket = poll.data().path("ticket").asText();
        assertThat(ticket).hasSize(43);

        // exchange → 200 双 Token；DB 置 COMPLETED；Redis ticket 删除
        var exchanged = post("/api/v1/auth/qr/tickets/exchange", Map.of("ticket", ticket), null);
        assertThat(exchanged.code()).as("body=%s", exchanged.body()).isEqualTo("OK");
        assertThat(exchanged.data().path("accessToken").asText()).isNotBlank();
        assertThat(exchanged.data().path("user").path("openidBound").asBoolean()).isTrue();

        // 二次兑换 → 401 WECHAT_TICKET_INVALID（ticket 已 GETDEL）
        var replay = post("/api/v1/auth/qr/tickets/exchange", Map.of("ticket", ticket), null);
        assertThat(replay.code()).isEqualTo("WECHAT_TICKET_INVALID");

        // 轮询 → COMPLETED，ticket 为 null（限流 1 次/秒：等窗口翻页后再取状态）
        Thread.sleep(1100);
        var completed = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(completed.code()).as("body=%s", completed.body()).isEqualTo("OK");
        assertThat(completed.data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.data().path("ticket").isNull()).isTrue();
    }

    // ------------------------------------------------------------ register-and-confirm / bind-and-confirm

    @Test
    void qrRegisterAndConfirmCreatesQrAccountAndBindTokenIsOneTime() {
        String accessToken = wechatLogin("v1-reg-" + unique("c"));
        String sceneId = createSession();
        var confirmed = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), accessToken);
        String bindToken = confirmed.data().path("bindToken").asText();

        String username = unique("v1rq");
        var registered = post("/api/v1/auth/qr/sessions/" + sceneId + "/register-and-confirm",
                Map.of("bindToken", bindToken, "username", username,
                        "password", PASSWORD, "nickname", "扫码注册用户"), null);
        assertThat(registered.code()).as("body=%s", registered.body()).isEqualTo("OK");
        assertThat(registered.data().path("bindToken").isNull()).isTrue();

        // 正式账号落库：source=QR，openid 已绑定
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, (openid IS NOT NULL) AS bound FROM users WHERE username = ?",
                username);
        assertThat(row.get("source")).isEqualTo("QR");
        assertThat(((Number) row.get("bound")).intValue()).isEqualTo(1);

        // 同一 bindToken 复用 → 400 WECHAT_BIND_TOKEN_INVALID
        var replay = post("/api/v1/auth/qr/sessions/" + sceneId + "/register-and-confirm",
                Map.of("bindToken", bindToken, "username", unique("v1rq2"),
                        "password", PASSWORD), null);
        assertThat(replay.code()).isEqualTo("WECHAT_BIND_TOKEN_INVALID");

        // 会话 CONFIRMED、ticket 仍在（不消费）
        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(poll.data().path("ticket").asText()).isNotBlank();
    }

    @Test
    void qrBindAndConfirmRequiresLoginAndTargetsCurrentUser() {
        String userACode = "v1-bnda-" + unique("c");
        String userAToken = wechatLogin(userACode);
        String userBToken = wechatLogin("v1-bndb-" + unique("c"));
        String sceneId = createSession();
        var confirmed = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), userAToken);
        String bindToken = confirmed.data().path("bindToken").asText();

        // 仅 bindToken 无登录态 → 401（bindToken 未被消费，随后仍可用）
        var anonymous = post("/api/v1/auth/qr/sessions/" + sceneId + "/bind-and-confirm",
                Map.of("bindToken", bindToken), null);
        assertThat(anonymous.code()).isEqualTo("AUTH_UNAUTHORIZED");

        // 用户 B（已绑自己的 openid）携带 bindToken → 409 WECHAT_ACCOUNT_ALREADY_BOUND
        var wrongUser = post("/api/v1/auth/qr/sessions/" + sceneId + "/bind-and-confirm",
                Map.of("bindToken", bindToken), userBToken);
        assertThat(wrongUser.code()).isEqualTo("WECHAT_ACCOUNT_ALREADY_BOUND");

        // 用户 A 本人 → 确认成功；轮询 CONFIRMED
        var own = post("/api/v1/auth/qr/sessions/" + sceneId + "/bind-and-confirm",
                Map.of("bindToken", bindToken), userAToken);
        assertThat(own.code()).as("body=%s", own.body()).isEqualTo("OK");
        assertThat(get("/api/v1/auth/qr/sessions/" + sceneId, null).data()
                .path("status").asText()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------ 过期与限流

    @Test
    void expiredSessionPollsExpiredAndConfirmRejected() {
        String accessToken = wechatLogin("v1-exp-" + unique("c"));
        String sceneId = createSession();
        jdbc.update("UPDATE wechat_qr_sessions SET expires_at = UTC_TIMESTAMP() - 1 "
                + "WHERE scene_id = ?", sceneId);

        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.data().path("status").asText()).isEqualTo("EXPIRED");
        assertThat(poll.data().path("ticket").isNull()).isTrue();

        var confirm = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), accessToken);
        assertThat(confirm.code()).isEqualTo("WECHAT_QR_EXPIRED");
    }

    @Test
    void pollRateLimitedToOnePerSecondPerSceneAndIp() {
        String sceneId = createSession();
        var first = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(first.code()).as("body=%s", first.body()).isEqualTo("OK");
        var second = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(second.code()).isEqualTo("COMMON_RATE_LIMITED");
    }
}
