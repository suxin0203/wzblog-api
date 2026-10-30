package com.wzblog.modules.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.api.dto.AdminUpdateUserRequest;
import com.wzblog.modules.identity.domain.UserStatus;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 身份认证集成测试（图形验证码关闭模式）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    // ------------------------------------------------------------ 注册

    @Test
    void registerSuccessThenDuplicateUsernameAndEmail() {
        String username = unique("reg");
        String email = username + "@test.local";

        HttpResult created = registerRaw(username, PASSWORD, email);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.code()).isEqualTo("OK");
        JsonNode data = created.data();
        assertThat(data.path("id").isTextual()).isTrue();
        assertThat(data.path("username").asText()).isEqualTo(username);
        assertThat(data.path("email").asText()).isEqualTo(email);
        assertThat(data.path("role").asText()).isEqualTo("USER");
        assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.has("password")).isFalse();
        assertThat(data.has("passwordHash")).isFalse();

        Map<String, Object> dbRow = jdbc.queryForMap(
                "SELECT role, status, nickname, password_hash FROM users WHERE username = ?", username);
        assertThat(dbRow.get("role")).isEqualTo("USER");
        assertThat(dbRow.get("status")).isEqualTo("ACTIVE");
        assertThat(dbRow.get("nickname")).isEqualTo(username);
        assertThat((String) dbRow.get("password_hash")).startsWith("$2");

        HttpResult duplicateName = registerRaw(username, PASSWORD, null);
        assertThat(duplicateName.status()).isEqualTo(409);
        assertThat(duplicateName.code()).isEqualTo("USER_USERNAME_TAKEN");

        HttpResult duplicateEmail = registerRaw(unique("reg"), PASSWORD, email);
        assertThat(duplicateEmail.status()).isEqualTo(409);
        assertThat(duplicateEmail.code()).isEqualTo("USER_EMAIL_TAKEN");
    }

    @Test
    void registerRejectsWeakPasswordAndInvalidUsername() {
        HttpResult weak = registerRaw(unique("wk"), "abc123", null);
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.code()).isEqualTo("COMMON_VALIDATION_ERROR");
        assertThat(weak.error("password")).isNotBlank();

        HttpResult shortName = registerRaw("ab", PASSWORD, null);
        assertThat(shortName.status()).isEqualTo(400);
        assertThat(shortName.error("username")).isNotBlank();

        HttpResult reservedName = registerRaw("deleted_abc", PASSWORD, null);
        assertThat(reservedName.status()).isEqualTo(400);
        assertThat(reservedName.error("username")).isNotBlank();
    }

    @Test
    void registerAndLoginWorkWhenCaptchaDisabled() {
        String username = unique("nocap");
        Map<String, Object> registerBody = new LinkedHashMap<>();
        registerBody.put("username", username);
        registerBody.put("password", PASSWORD);
        HttpResult created = post("/api/v1/auth/register", registerBody, null);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.data().path("id").isTextual()).isTrue();

        HttpResult login = post("/api/v1/auth/login",
                Map.of("username", username, "password", PASSWORD), null);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data().path("accessToken").asText()).isNotBlank();
    }

    // ------------------------------------------------------------ 登录

    @Test
    void loginSuccessWritesAuditAndUpdatesLastLogin() {
        String username = unique("lg");
        register(username, PASSWORD, username + "@test.local");

        HttpResult login = loginRaw(username, PASSWORD, "junit-agent");
        assertThat(login.status()).isEqualTo(200);
        JsonNode data = login.data();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(data.path("refreshExpiresIn").asInt()).isEqualTo(1209600);
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).hasSize(43);

        String refreshToken = data.path("refreshToken").asText();
        Long id = userId(username);
        Integer successAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_audits WHERE user_id = ? AND success = 1", Integer.class, id);
        assertThat(successAudits).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_login_at, last_login_ip FROM users WHERE id = ?", id);
        assertThat(row.get("last_login_at")).isNotNull();
        assertThat(row.get("last_login_ip")).isNotNull();
        Integer tokens = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, id);
        assertThat(tokens).isEqualTo(1);
        assertThat(sha256(refreshToken)).isNotBlank();
        Integer storedByHash = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, sha256(refreshToken));
        assertThat(storedByHash).isEqualTo(1);
    }

    @Test
    void loginLocksAccountAfterFiveFailures() {
        String username = unique("lock");
        register(username, PASSWORD, null);

        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpResult wrong = loginRaw(username, "WrongPass1");
            assertThat(wrong.status()).as("attempt " + attempt).isEqualTo(422);
            assertThat(wrong.code()).isEqualTo("AUTH_BAD_CREDENTIALS");
        }
        HttpResult fifth = loginRaw(username, "WrongPass1");
        assertThat(fifth.status()).isEqualTo(429);
        assertThat(fifth.code()).isEqualTo("AUTH_ACCOUNT_LOCKED");
        assertThat(fifth.headers().getFirst("Retry-After")).isNotBlank();

        HttpResult locked = loginRaw(username, PASSWORD);
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.code()).isEqualTo("AUTH_ACCOUNT_LOCKED");

        Long id = userId(username);
        Integer badCredentials = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_audits WHERE user_id = ? AND failure_reason = 'BAD_CREDENTIALS'",
                Integer.class, id);
        Integer lockedAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_audits WHERE user_id = ? AND failure_reason = 'LOCKED'",
                Integer.class, id);
        assertThat(badCredentials).isGreaterThanOrEqualTo(4);
        assertThat(lockedAudits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void disabledAccountIsRejectedForLoginAccessAndRefresh() {
        String username = unique("dis");
        register(username, PASSWORD, null);
        HttpResult login = loginRaw(username, PASSWORD);
        String access = login.data().path("accessToken").asText();
        String refresh = login.data().path("refreshToken").asText();

        Long id = userId(username);
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", id);

        HttpResult loginResult = loginRaw(username, PASSWORD);
        assertThat(loginResult.status()).isEqualTo(403);
        assertThat(loginResult.code()).isEqualTo("AUTH_ACCOUNT_DISABLED");

        HttpResult me = get("/api/v1/users/me", access);
        assertThat(me.status()).isEqualTo(403);
        assertThat(me.code()).isEqualTo("AUTH_ACCOUNT_DISABLED");

        HttpResult refreshResult = post("/api/v1/auth/refresh", Map.of("refreshToken", refresh), null);
        assertThat(refreshResult.status()).isEqualTo(403);
        assertThat(refreshResult.code()).isEqualTo("AUTH_ACCOUNT_DISABLED");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, id);
        assertThat(active).isZero();
    }

    // ------------------------------------------------------------ 刷新与登出

    @Test
    void refreshRotatesTokenAndReuseRevokesFamily() {
        String username = unique("rfr");
        register(username, PASSWORD, null);
        HttpResult login = loginRaw(username, PASSWORD, "device-a");
        String oldRefresh = login.data().path("refreshToken").asText();

        HttpResult rotated = post("/api/v1/auth/refresh", Map.of("refreshToken", oldRefresh), null, "device-a");
        assertThat(rotated.status()).isEqualTo(200);
        String newRefresh = rotated.data().path("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(rotated.data().path("accessToken").asText()).isNotBlank();

        Map<String, Object> oldRow = jdbc.queryForMap(
                "SELECT id, family_id, revoked_at, replaced_by_id FROM refresh_tokens WHERE token_hash = ?",
                sha256(oldRefresh));
        assertThat(oldRow.get("revoked_at")).isNotNull();
        assertThat(oldRow.get("replaced_by_id")).isNotNull();
        String familyId = (String) oldRow.get("family_id");
        Map<String, Object> newRow = jdbc.queryForMap(
                "SELECT family_id, parent_id FROM refresh_tokens WHERE token_hash = ?", sha256(newRefresh));
        assertThat(newRow.get("family_id")).isEqualTo(familyId);
        assertThat(((Number) newRow.get("parent_id")).longValue())
                .isEqualTo(((Number) oldRow.get("id")).longValue());

        // 10 秒内但 UA 不同 → 判定复用并吊销整个 family
        HttpResult reused = post("/api/v1/auth/refresh", Map.of("refreshToken", oldRefresh), null, "device-b");
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.code()).isEqualTo("AUTH_REFRESH_REUSED");
        Integer activeInFamily = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class, familyId);
        assertThat(activeInFamily).isZero();

        HttpResult afterRevoke = post("/api/v1/auth/refresh", Map.of("refreshToken", newRefresh), null, "device-a");
        assertThat(afterRevoke.status()).isEqualTo(401);
        assertThat(afterRevoke.code()).isEqualTo("AUTH_REFRESH_INVALID");
    }

    @Test
    void logoutRevokesFamilyAndBlacklistsAccessToken() {
        String username = unique("out");
        register(username, PASSWORD, null);
        HttpResult login = loginRaw(username, PASSWORD, "device-a");
        String access = login.data().path("accessToken").asText();
        String refresh = login.data().path("refreshToken").asText();

        HttpResult logout = post("/api/v1/auth/logout", Map.of("refreshToken", refresh), access);
        assertThat(logout.status()).isEqualTo(200);

        HttpResult me = get("/api/v1/users/me", access);
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("AUTH_TOKEN_INVALID");

        HttpResult refreshResult = post("/api/v1/auth/refresh", Map.of("refreshToken", refresh), null);
        assertThat(refreshResult.status()).isEqualTo(401);
        assertThat(refreshResult.code()).isEqualTo("AUTH_REFRESH_INVALID");

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId(username));
        assertThat(active).isZero();
    }

    @Test
    void logoutAllRevokesEveryFamily() {
        String username = unique("outa");
        register(username, PASSWORD, null);
        HttpResult first = loginRaw(username, PASSWORD, "device-a");
        HttpResult second = loginRaw(username, PASSWORD, "device-b");

        HttpResult logoutAll = post("/api/v1/auth/logout-all", null, first.data().path("accessToken").asText());
        assertThat(logoutAll.status()).isEqualTo(200);

        for (HttpResult login : List.of(first, second)) {
            HttpResult refreshResult = post("/api/v1/auth/refresh",
                    Map.of("refreshToken", login.data().path("refreshToken").asText()), null);
            assertThat(refreshResult.status()).isEqualTo(401);
            assertThat(refreshResult.code()).isEqualTo("AUTH_REFRESH_INVALID");
        }
    }

    @Test
    void missingOrInvalidTokenReturnsJson401() {
        HttpResult missing = get("/api/v1/users/me", null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.code()).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(missing.body().path("traceId").asText()).isNotBlank();

        HttpResult invalid = exchange(org.springframework.http.HttpMethod.GET, "/api/v1/users/me",
                null, "not-a-jwt", null);
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(invalid.code()).isEqualTo("AUTH_TOKEN_INVALID");

        HttpResult logoutWithoutToken = post("/api/v1/auth/logout",
                Map.of("refreshToken", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), null);
        assertThat(logoutWithoutToken.status()).isEqualTo(401);
        assertThat(logoutWithoutToken.code()).isEqualTo("AUTH_UNAUTHORIZED");

        HttpResult malformedRefresh = post("/api/v1/auth/refresh", Map.of("refreshToken", "short"), null);
        assertThat(malformedRefresh.status()).isEqualTo(401);
        assertThat(malformedRefresh.code()).isEqualTo("AUTH_REFRESH_INVALID");
    }

    @Test
    void invalidTokenDoesNotBlockPublicEndpoint() {
        HttpResult ping = exchange(org.springframework.http.HttpMethod.GET, "/api/v1/ping",
                null, "not-a-jwt", null);
        assertThat(ping.status()).isEqualTo(200);
        assertThat(ping.code()).isEqualTo("OK");
    }

    // ------------------------------------------------------------ 资料与会话

    @Test
    void currentUserProfileAndPublicProfileWhitelist() {
        String username = unique("prf");
        String email = username + "@test.local";
        String userId = register(username, PASSWORD, email);
        String access = accessToken(username, PASSWORD);

        HttpResult me = get("/api/v1/users/me", access);
        assertThat(me.status()).isEqualTo(200);
        JsonNode self = me.data();
        assertThat(self.path("id").isTextual()).isTrue();
        assertThat(self.path("email").asText()).isEqualTo(email);
        assertThat(self.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(self.path("activeTitle").isNull()).isTrue();
        assertThat(self.path("createdAt").asText()).endsWith("Z");

        // 头像引用需真实 files 行（M5 claimByKey 生效）
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', 'avatar/2026/10/a.png', ?, 'AVATAR', 'ACTIVE', 0, 100)
                """, Long.valueOf(userId));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("nickname", "新昵称");
        update.put("bio", "写代码，也写字。");
        update.put("avatarKey", "avatar/2026/10/a.png");
        update.put("role", "ADMIN");
        update.put("status", "DISABLED");
        HttpResult updated = put("/api/v1/users/me", update, access);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("nickname").asText()).isEqualTo("新昵称");
        assertThat(updated.data().path("role").asText()).isEqualTo("USER");
        assertThat(updated.data().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(updated.data().path("avatarUrl").asText()).contains("avatar/2026/10/a.png");

        HttpResult profile = get("/api/v1/users/" + userId + "/profile", null);
        assertThat(profile.status()).isEqualTo(200);
        JsonNode publicProfile = profile.data();
        assertThat(publicProfile.path("id").asText()).isEqualTo(userId);
        assertThat(publicProfile.path("nickname").asText()).isEqualTo("新昵称");
        assertThat(publicProfile.size()).isEqualTo(8);
        assertThat(publicProfile.has("email")).isFalse();
        assertThat(publicProfile.has("openid")).isFalse();
        assertThat(publicProfile.has("status")).isFalse();
        assertThat(publicProfile.has("lastLoginAt")).isFalse();

        HttpResult missing = get("/api/v1/users/99999999/profile", null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void updateProfileAllowsMissingNicknamePerContract() {
        String username = unique("prf2");
        register(username, PASSWORD, null);
        String access = accessToken(username, PASSWORD);

        HttpResult before = get("/api/v1/users/me", access);
        String originalNickname = before.data().path("nickname").asText();

        // 契约 UpdateProfileRequest.required 为空：仅改 bio 不应 400，昵称保持原值
        HttpResult bioOnly = put("/api/v1/users/me", Map.of("bio", "only bio"), access);
        assertThat(bioOnly.status()).as("bioOnly: %s", bioOnly.body()).isEqualTo(200);
        assertThat(bioOnly.data().path("nickname").asText()).isEqualTo(originalNickname);
        assertThat(bioOnly.data().path("bio").asText()).isEqualTo("only bio");

        // 空字符串昵称同样保持原值（不落空昵称）
        HttpResult blankNickname = put("/api/v1/users/me",
                Map.of("nickname", "", "bio", "blank nickname"), access);
        assertThat(blankNickname.status()).as("blankNickname: %s", blankNickname.body()).isEqualTo(200);
        assertThat(blankNickname.data().path("nickname").asText()).isEqualTo(originalNickname);

        // 超长昵称仍拒绝
        HttpResult tooLong = put("/api/v1/users/me", Map.of("nickname", "x".repeat(31)), access);
        assertThat(tooLong.status()).isEqualTo(400);
    }

    @Test
    void changePasswordRequiresOldPasswordAndRevokesAllSessions() {
        String username = unique("pwd");
        register(username, PASSWORD, null);
        HttpResult first = loginRaw(username, PASSWORD, "device-a");
        HttpResult second = loginRaw(username, PASSWORD, "device-b");
        String access = first.data().path("accessToken").asText();

        HttpResult wrongOld = put("/api/v1/users/me/password",
                Map.of("oldPassword", "WrongPass1", "newPassword", "NewPassw0rd"), access);
        assertThat(wrongOld.status()).isEqualTo(422);
        assertThat(wrongOld.code()).isEqualTo("AUTH_OLD_PASSWORD_MISMATCH");

        HttpResult samePassword = put("/api/v1/users/me/password",
                Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD), access);
        assertThat(samePassword.status()).isEqualTo(422);
        assertThat(samePassword.code()).isEqualTo("AUTH_PASSWORD_UNCHANGED");

        HttpResult changed = put("/api/v1/users/me/password",
                Map.of("oldPassword", PASSWORD, "newPassword", "NewPassw0rd"), access);
        assertThat(changed.status()).isEqualTo(200);

        for (HttpResult login : List.of(first, second)) {
            HttpResult refreshResult = post("/api/v1/auth/refresh",
                    Map.of("refreshToken", login.data().path("refreshToken").asText()), null);
            assertThat(refreshResult.status()).isEqualTo(401);
            assertThat(refreshResult.code()).isEqualTo("AUTH_REFRESH_INVALID");
        }
        HttpResult me = get("/api/v1/users/me", access);
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("AUTH_TOKEN_INVALID");

        assertThat(loginRaw(username, PASSWORD).status()).isEqualTo(422);
        assertThat(loginRaw(username, "NewPassw0rd").status()).isEqualTo(200);
    }

    @Test
    void sessionsListAndDeleteOwnership() {
        String username = unique("ses");
        register(username, PASSWORD, null);
        HttpResult first = loginRaw(username, PASSWORD, "device-a");
        loginRaw(username, PASSWORD, "device-b");
        String access = first.data().path("accessToken").asText();

        HttpResult sessions = get("/api/v1/users/me/sessions", access);
        assertThat(sessions.status()).isEqualTo(200);
        JsonNode list = sessions.data();
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(2);
        String sessionId = list.get(0).path("sessionId").asText();
        assertThat(sessionId).isNotBlank();
        assertThat(list.get(0).path("userAgent").asText()).isNotBlank();

        // 他人会话按 404 处理
        String otherUsername = unique("seso");
        register(otherUsername, PASSWORD, null);
        String otherAccess = accessToken(otherUsername, PASSWORD);
        HttpResult deleteOther = delete("/api/v1/users/me/sessions/" + sessionId, otherAccess);
        assertThat(deleteOther.status()).isEqualTo(404);
        assertThat(deleteOther.code()).isEqualTo("USER_SESSION_NOT_FOUND");

        HttpResult deleted = delete("/api/v1/users/me/sessions/" + sessionId, access);
        assertThat(deleted.status()).isEqualTo(200);
        HttpResult again = delete("/api/v1/users/me/sessions/" + sessionId, access);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.code()).isEqualTo("USER_SESSION_NOT_FOUND");

        HttpResult remaining = get("/api/v1/users/me/sessions", access);
        assertThat(remaining.data().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 管理员

    @Test
    void adminUserLifecycleAndRegularUserForbidden() {
        String admin = adminToken();
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'bootstrap_admin'", Long.class);

        HttpResult list = get("/api/v1/admin/users?page=1&pageSize=5", admin);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().path("list").isArray()).isTrue();
        assertThat(list.data().path("list").size()).isLessThanOrEqualTo(5);
        assertThat(list.data().path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(list.data().path("list").get(0).path("id").isTextual()).isTrue();

        String username = unique("adm");
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("username", username);
        createBody.put("password", PASSWORD);
        createBody.put("nickname", "后台创建");
        createBody.put("email", username + "@test.local");
        HttpResult created = post("/api/v1/admin/users", createBody, admin);
        assertThat(created.status()).isEqualTo(200);
        String id = created.data().path("id").asText();
        assertThat(id).matches("\\d+");

        HttpResult duplicate = post("/api/v1/admin/users", createBody, admin);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("USER_USERNAME_TAKEN");

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("nickname", "改名后");
        updateBody.put("role", "EDITOR");
        updateBody.put("status", "ACTIVE");
        updateBody.put("email", username + ".new@test.local");
        HttpResult updated = put("/api/v1/admin/users/" + id, updateBody, admin);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("role").asText()).isEqualTo("EDITOR");
        Map<String, Object> dbRow = jdbc.queryForMap(
                "SELECT nickname, role, email FROM users WHERE id = ?", Long.valueOf(id));
        assertThat(dbRow.get("nickname")).isEqualTo("改名后");
        assertThat(dbRow.get("role")).isEqualTo("EDITOR");
        assertThat(dbRow.get("email")).isEqualTo(username + ".new@test.local");

        HttpResult detail = get("/api/v1/admin/users/" + id, admin);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path("username").asText()).isEqualTo(username);
        assertThat(detail.data().has("passwordHash")).isFalse();

        HttpResult deleted = delete("/api/v1/admin/users/" + id, admin);
        assertThat(deleted.status()).isEqualTo(200);
        Map<String, Object> deletedRow = jdbc.queryForMap(
                "SELECT username, email, nickname, status, deleted_at, avatar_key, password_hash FROM users WHERE id = ?",
                Long.valueOf(id));
        assertThat(deletedRow.get("username")).isEqualTo("deleted_" + id);
        assertThat(deletedRow.get("email")).isEqualTo("deleted_" + id + "@deleted.invalid");
        assertThat(deletedRow.get("nickname")).isEqualTo("已注销用户");
        assertThat(deletedRow.get("status")).isEqualTo("DISABLED");
        assertThat(deletedRow.get("deleted_at")).isNotNull();
        assertThat(deletedRow.get("avatar_key")).isNull();
        assertThat((String) deletedRow.get("password_hash")).startsWith("$2");

        HttpResult loginDeleted = loginRaw(username, PASSWORD);
        assertThat(loginDeleted.status()).isEqualTo(422);
        assertThat(loginDeleted.code()).isEqualTo("AUTH_BAD_CREDENTIALS");

        HttpResult detailAfterDelete = get("/api/v1/admin/users/" + id, admin);
        assertThat(detailAfterDelete.status()).isEqualTo(404);
        assertThat(detailAfterDelete.code()).isEqualTo("USER_NOT_FOUND");

        HttpResult selfDelete = delete("/api/v1/admin/users/" + adminId, admin);
        assertThat(selfDelete.status()).isEqualTo(422);
        assertThat(selfDelete.code()).isEqualTo("USER_CANNOT_DELETE_SELF");

        String normalUser = unique("usr");
        register(normalUser, PASSWORD, null);
        String userToken = accessToken(normalUser, PASSWORD);
        HttpResult forbidden = get("/api/v1/admin/users", userToken);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("AUTH_FORBIDDEN");
        HttpResult forbiddenCreate = post("/api/v1/admin/users", createBody, userToken);
        assertThat(forbiddenCreate.status()).isEqualTo(403);
    }

    @Test
    void adminCannotChangeOwnRoleAndLastAdminIsProtected() {
        String admin = adminToken();
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'bootstrap_admin'", Long.class);

        Map<String, Object> selfDemote = new LinkedHashMap<>();
        selfDemote.put("nickname", "bootstrap_admin");
        selfDemote.put("role", "USER");
        selfDemote.put("status", "ACTIVE");
        HttpResult selfChange = put("/api/v1/admin/users/" + adminId, selfDemote, admin);
        assertThat(selfChange.status()).isEqualTo(403);
        assertThat(selfChange.code()).isEqualTo("AUTH_FORBIDDEN");

        Map<String, Object> selfRename = new LinkedHashMap<>();
        selfRename.put("nickname", "超级管理员");
        selfRename.put("role", "ADMIN");
        selfRename.put("status", "ACTIVE");
        HttpResult renamed = put("/api/v1/admin/users/" + adminId, selfRename, admin);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.data().path("nickname").asText()).isEqualTo("超级管理员");

        // 最后一个可用 ADMIN：把其他 ADMIN 临时降级后，唯一的 ADMIN 不能被降级
        String username = unique("last");
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("username", username);
        createBody.put("password", PASSWORD);
        createBody.put("nickname", "唯一管理员");
        createBody.put("role", "ADMIN");
        createBody.put("status", "ACTIVE");
        HttpResult created = post("/api/v1/admin/users", createBody, admin);
        assertThat(created.status()).isEqualTo(200);
        Long lastAdminId = Long.valueOf(created.data().path("id").asText());

        List<Long> otherAdmins = jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'ADMIN' AND deleted_at IS NULL AND id <> ?",
                Long.class, lastAdminId);
        for (Long id : otherAdmins) {
            jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", id);
        }
        try {
            AdminUpdateUserRequest demote = new AdminUpdateUserRequest(
                    "唯一管理员", Role.USER, UserStatus.ACTIVE, null, null);
            assertThatThrownBy(() -> adminUserAppService.update(
                    new CurrentUser(999_999L, Role.ADMIN), lastAdminId, demote))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isEqualTo(ErrorCode.USER_LAST_ADMIN));
        } finally {
            for (Long id : otherAdmins) {
                jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id);
            }
        }
    }

    @Test
    void adminReadsLoginAuditsNewestFirst() {
        String admin = adminToken();
        String username = unique("aud");
        register(username, PASSWORD, null);
        Long id = userId(username);
        loginRaw(username, PASSWORD, "device-a");
        loginRaw(username, PASSWORD, "device-b");

        HttpResult audits = get("/api/v1/admin/users/" + id + "/login-audits?page=1&pageSize=10", admin);
        assertThat(audits.status()).isEqualTo(200);
        JsonNode list = audits.data().path("list");
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        assertThat(list.get(0).path("userId").isTextual()).isTrue();
        assertThat(list.get(0).path("success").asBoolean()).isTrue();
        assertThat(list.get(0).path("createdAt").asText()).endsWith("Z");
        assertThat(list.get(0).has("passwordHash")).isFalse();

        HttpResult onlySuccess = get(
                "/api/v1/admin/users/" + id + "/login-audits?success=true&page=1&pageSize=10", admin);
        assertThat(onlySuccess.status()).isEqualTo(200);
        assertThat(onlySuccess.data().path("list").size()).isGreaterThanOrEqualTo(2);
        onlySuccess.data().path("list").forEach(node -> assertThat(node.path("success").asBoolean()).isTrue());

        String userToken = accessToken(username, PASSWORD);
        HttpResult forbidden = get("/api/v1/admin/users/" + id + "/login-audits", userToken);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("AUTH_FORBIDDEN");
    }

    // ------------------------------------------------------------ 找回密码

    @Test
    void forgotPasswordRequestVerifyResetFlow() {
        String username = unique("fgt");
        String email = username + "@test.local";
        register(username, PASSWORD, email);
        HttpResult firstLogin = loginRaw(username, PASSWORD, "device-a");
        loginRaw(username, PASSWORD, "device-b");

        HttpResult request = post("/api/v1/auth/password/forgot/request", Map.of("email", email), null);
        assertThat(request.status()).isEqualTo(200);
        assertThat(request.data().path("sent").asBoolean()).isTrue();
        assertThat(request.data().path("expiresIn").asInt()).isEqualTo(300);

        HttpResult unknown = post("/api/v1/auth/password/forgot/request",
                Map.of("email", "nobody-" + unique("x") + "@test.local"), null);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.data().path("sent").asBoolean()).isTrue();

        // 直接写入已知 code_hash（与 SHA-256(target:code:pepper) 口径一致），并作废旧码
        String code = "135790";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE verification_codes SET consumed_at = ?
                WHERE scene = 'RESET' AND target = ? AND consumed_at IS NULL
                """, now, email);
        jdbc.update("""
                INSERT INTO verification_codes (scene, target, code_hash, expires_at, attempt_count, created_at)
                VALUES ('RESET', ?, ?, ?, 0, ?)
                """, email, sha256(email + ":" + code + ":test-pepper"),
                now.plusMinutes(5), now);

        HttpResult wrongCode = post("/api/v1/auth/password/forgot/verify",
                Map.of("email", email, "code", "000000"), null);
        assertThat(wrongCode.status()).isEqualTo(400);
        assertThat(wrongCode.code()).isEqualTo("AUTH_VERIFICATION_CODE_INVALID");

        HttpResult verify = post("/api/v1/auth/password/forgot/verify",
                Map.of("email", email, "code", code), null);
        assertThat(verify.status()).isEqualTo(200);
        String resetToken = verify.data().path("resetToken").asText();
        assertThat(resetToken).hasSize(43);
        assertThat(verify.data().path("expiresIn").asInt()).isEqualTo(300);

        HttpResult reset = post("/api/v1/auth/password/forgot/reset",
                Map.of("resetToken", resetToken, "newPassword", "ResetPassw0rd"), null);
        assertThat(reset.status()).isEqualTo(200);

        HttpResult reuseToken = post("/api/v1/auth/password/forgot/reset",
                Map.of("resetToken", resetToken, "newPassword", "ResetPassw0rd"), null);
        assertThat(reuseToken.status()).isEqualTo(410);
        assertThat(reuseToken.code()).isEqualTo("AUTH_RESET_TOKEN_EXPIRED");

        HttpResult recycledCode = post("/api/v1/auth/password/forgot/verify",
                Map.of("email", email, "code", code), null);
        assertThat(recycledCode.status()).isEqualTo(400);
        assertThat(recycledCode.code()).isEqualTo("AUTH_VERIFICATION_CODE_EXPIRED");

        HttpResult oldPasswordLogin = loginRaw(username, PASSWORD);
        assertThat(oldPasswordLogin.status()).isEqualTo(422);
        HttpResult newPasswordLogin = loginRaw(username, "ResetPassw0rd");
        assertThat(newPasswordLogin.status()).isEqualTo(200);

        HttpResult oldRefresh = post("/api/v1/auth/refresh",
                Map.of("refreshToken", firstLogin.data().path("refreshToken").asText()), null);
        assertThat(oldRefresh.status()).isEqualTo(401);
        assertThat(oldRefresh.code()).isEqualTo("AUTH_REFRESH_INVALID");
    }

    /** G8：注销清空 avatar_key 的同时必须释放旧头像引用，否则文件永远无法被清理。 */
    @Test
    void deleteUserReleasesAvatarReference() {
        String username = unique("delav");
        String id = register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        String avatarKey = "avatars/2026/10/" + unique("av") + ".png";
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', ?, ?, 'AVATAR', 'ACTIVE', 0, 100)
                """, avatarKey, Long.valueOf(id));
        assertThat(put("/api/v1/users/me",
                Map.of("nickname", "头像用户", "avatarKey", avatarKey), token).status()).isEqualTo(200);
        assertThat(avatarRefCount(avatarKey)).isEqualTo(1);

        assertThat(delete("/api/v1/admin/users/" + id, adminToken()).status()).isEqualTo(200);
        assertThat(avatarRefCount(avatarKey)).as("注销必须释放头像引用").isZero();
    }

    private int avatarRefCount(String objectKey) {
        Integer count = jdbc.queryForObject("SELECT ref_count FROM files WHERE object_key = ?",
                Integer.class, objectKey);
        return count == null ? 0 : count;
    }
}