package com.wzblog.modules.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 兼容层集成测试（Mock 上游）：旧协议壳（数字 code/token 顶层/snake_case）、
 * 算术验证码（MD5）、Base64 密码、openid 注册即绑、扫码状态机（entry/login_ok/轮询一次性）、
 * bindToken 一次性、过期、刷新不轮换。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WechatCompatIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Legacy legacyPost(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Time", String.valueOf(System.currentTimeMillis()));
        var response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
        return new Legacy(response.getStatusCode().value(), response.getBody() == null ? null
                : parse(response.getBody()));
    }

    private Legacy legacyGet(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.set("X-Request-Time", String.valueOf(System.currentTimeMillis()));
        var response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return new Legacy(response.getStatusCode().value(), response.getBody() == null ? null
                : parse(response.getBody()));
    }

    record Legacy(int httpStatus, JsonNode body) {

        int code() {
            return body.path("code").asInt(-999);
        }

        JsonNode data() {
            return body.path("data");
        }
    }

    private String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String b64(String value) {
        return java.util.Base64.getEncoder()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 算术验证码：取题 → 计算 → MD5。 */
    private Map<String, String> fetchCaptcha() {
        Legacy captcha = legacyGet("/users/captcha", null);
        assertThat(captcha.httpStatus()).isEqualTo(200);
        assertThat(captcha.code()).isEqualTo(200);
        int num1 = captcha.data().path("num1").asInt();
        int num2 = captcha.data().path("num2").asInt();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("captchaId", captcha.data().path("captchaId").asText());
        result.put("captchaAnswer", md5(String.valueOf(num1 + num2)));
        return result;
    }

    // ------------------------------------------------------------ /wechat/* 壳

    @Test
    void openidProxyReturnsNoCodeFieldAndUserinfoIssuesTokens() {
        // code 换 openid：成功响应无 code 字段（request.js 格式3）
        Legacy openid = legacyGet("/wechat/openid/mock-code-1", null);
        assertThat(openid.httpStatus()).isEqualTo(200);
        assertThat(openid.body().has("code")).isFalse();
        assertThat(openid.body().path("message").asText()).isEqualTo("获取openid成功");
        String openidValue = openid.body().path("data").path("openid").asText();
        assertThat(openidValue).startsWith("mock-openid-");

        // 未绑定 → {code:0, 用户不存在}
        Legacy unbound = legacyGet("/wechat/userinfo/" + openidValue, null);
        assertThat(unbound.code()).isZero();
        assertThat(unbound.body().path("message").asText()).isEqualTo("用户不存在");

        // 注册（带 openid，Base64 密码 + MD5 验证码）→ token 顶层 + snake_case user
        Map<String, Object> register = new LinkedHashMap<>(fetchCaptcha());
        register.put("username", unique("wxu"));
        register.put("password", b64("Passw0rd1"));
        register.put("email", unique("wx") + "@test.local");
        register.put("openid", openidValue);
        Legacy registered = legacyPost("/users/register", register);
        assertThat(registered.code()).as("body=%s", registered.body()).isEqualTo(200);
        assertThat(registered.body().path("token").asText()).isNotBlank();
        assertThat(registered.body().path("refreshToken").asText()).isNotBlank();
        assertThat(registered.body().path("data").path("avatar_url").asText()).isNotBlank();
        assertThat(registered.body().path("data").path("created_at").asText()).isNotBlank();
        assertThat(registered.body().path("data").path("role").asText()).isEqualTo("user");

        // openid 已绑定 → userinfo 直接登录（data.refresh_token 供小程序 401 刷新）
        Legacy login = legacyGet("/wechat/userinfo/" + openidValue, null);
        assertThat(login.code()).isEqualTo(200);
        assertThat(login.body().path("token").asText()).isNotBlank();
        assertThat(login.body().path("data").path("refresh_token").asText()).isNotBlank();
    }

    @Test
    void legacyLoginWithCaptchaAndBase64PasswordAndRefreshNoRotation() {
        String username = unique("lg");
        register(username, PASSWORD, null);
        Map<String, Object> login = new LinkedHashMap<>(fetchCaptcha());
        login.put("username", username);
        login.put("password", b64(PASSWORD));
        Legacy loggedIn = legacyPost("/users/login", login);
        assertThat(loggedIn.code()).as("body=%s", loggedIn.body()).isEqualTo(200);
        String token = loggedIn.body().path("token").asText();
        String refreshToken = loggedIn.body().path("refreshToken").asText();

        // 错验证码 → 数字 code 400，HTTP 200
        Map<String, Object> badCaptcha = new LinkedHashMap<>();
        badCaptcha.put("username", username);
        badCaptcha.put("password", b64(PASSWORD));
        badCaptcha.put("captchaId", "bad-id");
        badCaptcha.put("captchaAnswer", md5("0"));
        Legacy failed = legacyPost("/users/login", badCaptcha);
        assertThat(failed.httpStatus()).isEqualTo(200);
        assertThat(failed.code()).isEqualTo(400);

        // /users/me：Bearer 可用；无 Token → HTTP 401（小程序刷新触发器）
        Legacy me = legacyGet("/users/me", token);
        assertThat(me.code()).isEqualTo(200);
        assertThat(me.data().path("username").asText()).isEqualTo(username);
        Legacy anonymous = rest.getForEntity("/users/me", String.class) == null ? null : null;
        var noAuth = rest.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()),
                String.class);
        assertThat(noAuth.getStatusCode().value()).isEqualTo(401);

        // 刷新：只换 access，refreshToken 不轮换（同一 refreshToken 可再次刷新）
        Map<String, Object> refreshBody = new LinkedHashMap<>();
        refreshBody.put("refreshToken", refreshToken);
        Legacy refreshed = legacyPost("/users/refresh", refreshBody);
        assertThat(refreshed.code()).isEqualTo(200);
        String newToken = refreshed.body().path("token").asText();
        assertThat(newToken).isNotEqualTo(token);
        assertThat(refreshed.body().path("refreshToken").asText()).isEmpty();
        // 旧 refreshToken 仍可用（未轮换）
        Legacy refreshedAgain = legacyPost("/users/refresh", refreshBody);
        assertThat(refreshedAgain.code()).isEqualTo(200);
        // 无效 refreshToken → 数字 401
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("refreshToken", "not-a-real-token");
        assertThat(legacyPost("/users/refresh", bad).code()).isEqualTo(401);
    }

    // ------------------------------------------------------------ 扫码全链路

    @Test
    void qrLoginFullChainForBoundUser() {
        // 造一个已绑定用户：先 openid 注册
        String openidCode = "mock-qr-" + unique("c");
        Legacy openid = legacyGet("/wechat/openid/" + openidCode, null);
        String openidValue = openid.body().path("data").path("openid").asText();
        Map<String, Object> register = new LinkedHashMap<>(fetchCaptcha());
        register.put("username", unique("qr"));
        register.put("password", b64("Passw0rd1"));
        register.put("email", unique("qr") + "@test.local");
        register.put("openid", openidValue);
        assertThat(legacyPost("/users/register", register).code()).isEqualTo(200);

        // PC 建会话：旧壳 + sceneId + dataURL 码
        Legacy session = legacyPost("/qr-login/session", Map.of());
        assertThat(session.code()).isEqualTo(200);
        String sceneId = session.data().path("sceneId").asText();
        assertThat(sceneId).hasSize(16).matches("[0-9A-Za-z]+");
        assertThat(session.data().path("miniProgramCode").asText()).startsWith("data:image/png;base64,");

        // 轮询 pending
        assertThat(legacyGet("/qr-login/session/" + sceneId, null).data().path("status").asText())
                .isEqualTo("pending");

        // 码 PNG（no-store）
        byte[] png = rest.getForObject("/qr-login/session/" + sceneId + "/code.png", byte[].class);
        assertThat(png).isNotNull();
        assertThat(png.length).isGreaterThan(100);

        // 小程序 entry：同一微信（同 openid 的 code）→ login_ok + 会话确认
        Legacy entry = legacyPost("/miniapp/qr-login/entry",
                Map.of("sceneId", sceneId, "code", openidCode));
        assertThat(entry.code()).as("body=%s", entry.body()).isEqualTo(200);
        assertThat(entry.data().path("action").asText()).isEqualTo("login_ok");
        assertThat(entry.data().path("client").path("ip").asText()).isNotBlank();

        // PC 轮询 confirmed：一次性 token + user（snake_case）
        Legacy confirmed = legacyGet("/qr-login/session/" + sceneId, null);
        assertThat(confirmed.code()).isEqualTo(200);
        assertThat(confirmed.data().path("status").asText()).isEqualTo("confirmed");
        assertThat(confirmed.data().path("token").asText()).isNotBlank();
        assertThat(confirmed.data().path("refreshToken").asText()).isNotBlank();
        assertThat(confirmed.data().path("user").path("avatar_url").asText()).isNotBlank();

        // 再轮询 → completed（Token 不再下发）
        Legacy completed = legacyGet("/qr-login/session/" + sceneId, null);
        assertThat(completed.data().path("status").asText()).isEqualTo("completed");
        assertThat(completed.data().has("token")).isFalse();
    }

    @Test
    void qrEntryUnboundIssuesBindTokenAndRegisterConfirmCompletes() {
        String openidCode = "mock-qr-" + unique("u");
        Legacy openid = legacyGet("/wechat/openid/" + openidCode, null);
        String openidValue = openid.body().path("data").path("openid").asText();

        Legacy session = legacyPost("/qr-login/session", Map.of());
        String sceneId = session.data().path("sceneId").asText();

        // 未绑定微信：entry → need_register_or_bind + bindToken
        Legacy entry = legacyPost("/miniapp/qr-login/entry",
                Map.of("sceneId", sceneId, "code", openidCode));
        assertThat(entry.data().path("action").asText()).isEqualTo("need_register_or_bind");
        String bindToken = entry.data().path("bindToken").asText();
        assertThat(bindToken).isNotBlank();

        // bindToken 一次性：注册并确认（Base64 密码；旧端点 email 必填语义保留为可空）
        Map<String, Object> register = new LinkedHashMap<>();
        register.put("sceneId", sceneId);
        register.put("bindToken", bindToken);
        register.put("username", unique("rq"));
        register.put("password", b64("Passw0rd1"));
        Legacy confirmed = legacyPost("/miniapp/qr-login/register-and-confirm", register);
        assertThat(confirmed.code()).as("body=%s", confirmed.body()).isEqualTo(200);
        assertThat(confirmed.data().path("status").asText()).isEqualTo("confirmed");

        // 同一 bindToken 二次使用 → 失败
        register.put("username", unique("rq2"));
        Legacy replay = legacyPost("/miniapp/qr-login/register-and-confirm", register);
        assertThat(replay.code()).isNotEqualTo(200);

        // PC 拿到 Token；openid 已绑定新账号
        Legacy poll = legacyGet("/qr-login/session/" + sceneId, null);
        assertThat(poll.data().path("token").asText()).isNotBlank();
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE openid = ?", Integer.class, openidValue);
        assertThat(bound).isEqualTo(1);
    }

    @Test
    void qrEntryBindAndConfirmForExistingAccount() {
        // 既有账号（密码注册）
        String username = unique("ba");
        register(username, PASSWORD, null);
        Long userId = userId(username);

        String openidCode = "mock-qr-" + unique("b");
        Legacy openid = legacyGet("/wechat/openid/" + openidCode, null);
        String openidValue = openid.body().path("data").path("openid").asText();

        Legacy session = legacyPost("/qr-login/session", Map.of());
        String sceneId = session.data().path("sceneId").asText();
        Legacy entry = legacyPost("/miniapp/qr-login/entry",
                Map.of("sceneId", sceneId, "code", openidCode));
        String bindToken = entry.data().path("bindToken").asText();

        // bind-and-confirm：账号密码 + bindToken → openid 绑到该账号，会话确认
        Map<String, Object> bind = new LinkedHashMap<>();
        bind.put("sceneId", sceneId);
        bind.put("bindToken", bindToken);
        bind.put("username", username);
        bind.put("password", b64(PASSWORD));
        Legacy bound = legacyPost("/miniapp/qr-login/bind-and-confirm", bind);
        assertThat(bound.code()).as("body=%s", bound.body()).isEqualTo(200);
        assertThat(bound.data().path("status").asText()).isEqualTo("confirmed");

        String storedOpenid = jdbc.queryForObject(
                "SELECT openid FROM users WHERE id = ?", String.class, userId);
        assertThat(storedOpenid).isEqualTo(openidValue);
        assertThat(legacyGet("/qr-login/session/" + sceneId, null)
                .data().path("token").asText()).isNotBlank();

        // 错密码 → 400
        Legacy session2 = legacyPost("/qr-login/session", Map.of());
        String sceneId2 = session2.data().path("sceneId").asText();
        Legacy entry2 = legacyPost("/miniapp/qr-login/entry",
                Map.of("sceneId", sceneId2, "code", "mock-qr-" + unique("c")));
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("sceneId", sceneId2);
        wrong.put("bindToken", entry2.data().path("bindToken").asText());
        wrong.put("username", username);
        wrong.put("password", b64("WrongPass1"));
        assertThat(legacyPost("/miniapp/qr-login/bind-and-confirm", wrong).code()).isEqualTo(400);
    }

    @Test
    void expiredSessionReturns410AndTimestampFilterRejectsSkewedRequest() throws Exception {
        Legacy session = legacyPost("/qr-login/session", Map.of());
        String sceneId = session.data().path("sceneId").asText();
        // 造过期
        jdbc.update(
                "UPDATE wechat_qr_sessions SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE scene_id = ?",
                sceneId);
        Thread.sleep(50);
        Legacy polled = legacyGet("/qr-login/session/" + sceneId, null);
        assertThat(polled.code()).isEqualTo(410);
        assertThat(polled.data().path("status").asText()).isEqualTo("expired");
        // 过期后 entry 也 410（Node 行为）
        Legacy entry = legacyPost("/miniapp/qr-login/entry",
                Map.of("sceneId", sceneId, "code", "mock-any"));
        assertThat(entry.code()).isEqualTo(410);

        // 时间戳偏差 > 容忍 → 400 数字码且不触达业务
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Time",
                String.valueOf(System.currentTimeMillis() - 3600_000));
        var skewed = rest.exchange("/qr-login/session", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers), String.class);
        assertThat(skewed.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(skewed.getBody()).path("code").asInt()).isEqualTo(400);

        // 未知 sceneId → 404
        assertThat(legacyGet("/qr-login/session/no-such-scene", null).code()).isEqualTo(404);
    }
}
