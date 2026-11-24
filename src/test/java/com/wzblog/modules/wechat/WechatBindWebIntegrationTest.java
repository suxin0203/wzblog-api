package com.wzblog.modules.wechat;

import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B18 可选增强集成测试（Mock 上游，test profile 验证码关闭）：
 * 场景 A 网页密码登录用户扫码绑定（轮询 mirror bindToken → /auth/wechat/bind-by-token 消费）；
 * 场景 B 扫码自动账号凭已有博客账号账密移交（/auth/wechat/bind-migrate：匿名化注销 +
 * 评论改挂目标账号 + 令牌轮换）；
 * 失败路径：无效 bindToken / 目标密码错误 / 目标已绑微信 / 非扫码来源拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WechatBindWebIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    private String wechatLogin(String code) {
        var response = post("/api/v1/auth/wechat/login", Map.of("code", code), null);
        assertThat(response.code()).as("body=%s", response.body()).isEqualTo("OK");
        return response.data().path("accessToken").asText();
    }

    private String createSession() {
        return createSession(null);
    }

    /** creatorToken 非空 = 网页已登录用户创建（绑定用途，服务端记录创建者归属）。 */
    private String createSession(String creatorToken) {
        var response = post("/api/v1/auth/qr/sessions", Map.of(), creatorToken);
        assertThat(response.code()).as("body=%s", response.body()).isEqualTo("OK");
        return response.data().path("sceneId").asText();
    }

    private long createPublishedArticle(String token) {
        String title = unique("art");
        var created = post("/api/v1/articles", articleBody(title, "# T\n\nbody"), token);
        assertThat(created.status()).as("create article: %s", created.body()).isEqualTo(200);
        long id = created.data().path("id").asLong();
        var published = post("/api/v1/articles/" + id + "/publish", Map.of(), token);
        assertThat(published.status()).as("publish: %s", published.body()).isEqualTo(200);
        return id;
    }

    private Map<String, Object> articleBody(String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("contentMd", content);
        return body;
    }

    // ------------------------------------------------------------ 场景 A：bind-by-token

    @Test
    void webUserBindsWechatViaBindToken() throws Exception {
        String username = unique("bindweb");
        register(username, PASSWORD, null);
        String webToken = accessToken(username, PASSWORD);

        // 小程序扫码确认（JWT 路径）→ CONFIRMED + bindToken
        String miniCode = "v1-bindweb-" + unique("c");
        var miniLogin = post("/api/v1/auth/wechat/login", Map.of("code", miniCode), null);
        String miniToken = miniLogin.data().path("accessToken").asText();
        String miniUserId = miniLogin.data().path("user").path("id").asText();
        // 会话须由网页用户（已登录）创建：bindToken 镜像与 bind-by-token 均校验创建者归属
        String sceneId = createSession(webToken);
        var confirmed = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), miniToken);
        assertThat(confirmed.code()).as("body=%s", confirmed.body()).isEqualTo("OK");

        // 网页轮询拿到一次性 bindToken
        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.data().path("status").asText()).isEqualTo("CONFIRMED");
        String bindToken = poll.data().path("bindToken").asText();
        assertThat(bindToken).as("poll=%s", poll.body()).hasSize(43);

        // 绑定 → openidBound=true；bindToken 一次性（镜像清除 + 消费后 400）
        var bound = post("/api/v1/auth/wechat/bind-by-token", Map.of("bindToken", bindToken), webToken);
        assertThat(bound.code()).as("body=%s", bound.body()).isEqualTo("OK");
        assertThat(bound.data().path("user").path("openidBound").asBoolean()).isTrue();

        // 轮询限流 1 次/秒：间隔 1.1s 再验（否则 429 空 data 会造成断言假绿）
        Thread.sleep(1100);
        var pollAgain = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(pollAgain.data().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(pollAgain.data().path("bindToken").asText()).isIn("", "null"); // 已消费：字段为 null
        var reused = post("/api/v1/auth/wechat/bind-by-token", Map.of("bindToken", bindToken), webToken);
        assertThat(reused.code()).isEqualTo("WECHAT_BIND_TOKEN_INVALID");

        // 自动账号已匿名化注销：同 code 再登录 → 新账号
        var relogin = post("/api/v1/auth/wechat/login", Map.of("code", miniCode), null);
        assertThat(relogin.code()).isEqualTo("OK");
        assertThat(relogin.data().path("user").path("id").asText()).isNotEqualTo(miniUserId);
    }

    /** 匿名创建的登录会话（登录流程）不产生 bindToken 镜像、不可网页绑定（P1 审查修复）。 */
    @Test
    void anonymousLoginSessionBindTokenCannotBeBound() {
        String username = unique("bindanon");
        register(username, PASSWORD, null);
        String webToken = accessToken(username, PASSWORD);

        String miniCode = "v1-bindanon-" + unique("c");
        String miniToken = wechatLogin(miniCode);
        String sceneId = createSession(); // 匿名创建（登录用途）
        var confirmed = post("/api/v1/auth/qr/sessions/" + sceneId + "/confirm", Map.of(), miniToken);
        assertThat(confirmed.code()).isEqualTo("OK");

        var poll = get("/api/v1/auth/qr/sessions/" + sceneId, null);
        assertThat(poll.data().path("status").asText()).isEqualTo("CONFIRMED");
        // 无创建者镜像：bindToken 为 null（响应显式序列化 null，asText 即 "null"）
        assertThat(poll.data().path("bindToken").asText()).isIn("", "null");

        // 即使拿到小程序侧 bindToken（同为 43 位一次性），非创建者仍不可网页绑定
        String miniBindToken = confirmed.data().path("bindToken").asText();
        var stolen = post("/api/v1/auth/wechat/bind-by-token",
                Map.of("bindToken", miniBindToken), webToken);
        assertThat(stolen.code()).isEqualTo("WECHAT_BIND_TOKEN_INVALID");
    }

    @Test
    void bindByTokenRejectsUnknownToken() {
        String username = unique("bindbad");
        register(username, PASSWORD, null);
        String webToken = accessToken(username, PASSWORD);
        var bad = post("/api/v1/auth/wechat/bind-by-token",
                Map.of("bindToken", "x".repeat(43)), webToken);
        assertThat(bad.code()).isEqualTo("WECHAT_BIND_TOKEN_INVALID");
    }

    // ------------------------------------------------------------ 场景 B：bind-migrate

    @Test
    void autoAccountMigratesToExistingBlogAccount() {
        String targetName = unique("migtarget");
        register(targetName, PASSWORD, null);

        String miniCode = "v1-migrate-" + unique("c");
        var miniLogin = post("/api/v1/auth/wechat/login", Map.of("code", miniCode), null);
        String autoToken = miniLogin.data().path("accessToken").asText();
        String autoUserId = miniLogin.data().path("user").path("id").asText();

        // 自动账号发一条评论（审核 PENDING 不影响归属迁移）
        long article = createPublishedArticle(adminToken());
        var comment = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", "迁移前的评论"), autoToken);
        assertThat(comment.status()).as("body=%s", comment.body()).isEqualTo(200);
        long commentId = comment.data().path("id").asLong();

        var migrated = post("/api/v1/auth/wechat/bind-migrate",
                Map.of("username", targetName, "password", PASSWORD), autoToken);
        assertThat(migrated.code()).as("body=%s", migrated.body()).isEqualTo("OK");
        assertThat(migrated.data().path("accessToken").asText()).isNotBlank();
        assertThat(migrated.data().path("user").path("openidBound").asBoolean()).isTrue();

        // 评论改挂目标账号
        Long ownerId = jdbc.queryForObject(
                "SELECT user_id FROM comments WHERE id = ?", Long.class, commentId);
        assertThat(ownerId).isEqualTo(userId(targetName));

        // 自动账号匿名化（软删 + username 改写 deleted_{id}）
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?", Object.class,
                Long.parseLong(autoUserId))).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT username FROM users WHERE id = ?", String.class,
                Long.parseLong(autoUserId))).startsWith("deleted_");

        // 同 code 再登录 → openid 已移交，落到目标账号
        var relogin = post("/api/v1/auth/wechat/login", Map.of("code", miniCode), null);
        assertThat(relogin.code()).isEqualTo("OK");
        assertThat(relogin.data().path("user").path("id").asText())
                .isEqualTo(String.valueOf(userId(targetName)));
    }

    @Test
    void bindMigrateRejectsWrongPasswordAndKeepsAutoAccount() {
        String targetName = unique("migp");
        register(targetName, PASSWORD, null);
        var miniLogin = post("/api/v1/auth/wechat/login",
                Map.of("code", "v1-migp-" + unique("c")), null);
        String autoToken = miniLogin.data().path("accessToken").asText();
        String autoUserId = miniLogin.data().path("user").path("id").asText();

        var bad = post("/api/v1/auth/wechat/bind-migrate",
                Map.of("username", targetName, "password", "WrongPass1"), autoToken);
        assertThat(bad.code()).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?",
                Object.class, Long.parseLong(autoUserId))).isNull();
    }

    @Test
    void bindMigrateRejectsWhenTargetAlreadyBound() {
        String targetName = unique("migb");
        register(targetName, PASSWORD, null);
        String targetToken = accessToken(targetName, PASSWORD);
        var targetBind = post("/api/v1/auth/wechat/bind",
                Map.of("code", "v1-migb-bind-" + unique("c")), targetToken);
        assertThat(targetBind.code()).isEqualTo("OK");

        String autoToken = wechatLogin("v1-migb-auto-" + unique("c"));
        var migrated = post("/api/v1/auth/wechat/bind-migrate",
                Map.of("username", targetName, "password", PASSWORD), autoToken);
        assertThat(migrated.code()).isEqualTo("WECHAT_TARGET_ALREADY_BOUND");
    }

    @Test
    void bindMigrateRejectsPasswordSourceAccount() {
        String name = unique("migsrc");
        register(name, PASSWORD, null);
        String token = accessToken(name, PASSWORD);
        var migrated = post("/api/v1/auth/wechat/bind-migrate",
                Map.of("username", name, "password", PASSWORD), token);
        assertThat(migrated.code()).isEqualTo("WECHAT_MIGRATE_SOURCE_INVALID");
    }
}
