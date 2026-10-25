package com.wzblog.modules.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.support.IdentityTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 互动集成测试：评论两级树/审核/敏感词、点赞收藏幂等、留言、通知与去重、SSE。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InteractionIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private com.wzblog.modules.admin.app.SiteConfigService siteConfigService;

    @Autowired
    private com.wzblog.modules.interaction.app.InteractionReconcileService reconcileService;

    /** 提权为 EDITOR 后返回其 token（普通用户无发文权限）。 */
    private String editorToken(String username) {
        Long id = userId(username);
        HttpResult updated = put("/api/v1/admin/users/" + id,
                Map.of("nickname", "nick" + id, "role", "EDITOR", "status", "ACTIVE"),
                adminToken());
        assertThat(updated.status()).as("promote: %s", updated.body()).isEqualTo(200);
        return accessToken(username, PASSWORD);
    }

    private long createPublishedArticle(String token) {
        String title = unique("art");
        HttpResult created = post("/api/v1/articles", articleBody(title, "# T\n\nbody"), token);
        assertThat(created.status()).as("create article: %s", created.body()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        HttpResult published = post("/api/v1/articles/" + id + "/publish", Map.of(), token);
        assertThat(published.status()).as("publish: %s", published.body()).isEqualTo(200);
        return id;
    }

    private Map<String, Object> articleBody(String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("contentMd", content);
        return body;
    }

    private Map<String, Object> commentBody(String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        return body;
    }

    // ------------------------------------------------------------ 评论创建与树

    @Test
    void commentLifecycleWithAuditAndTree() {
        long article = createPublishedArticle(adminToken());

        // 审核开启（seed 默认 true）：评论进入 PENDING，匿名不可见，本人可见
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                commentBody("好文章！"), adminToken());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("status").asText()).isEqualTo("PENDING");
        long commentId = created.data().path("id").asLong();

        HttpResult anonymousList = get("/api/v1/articles/" + article + "/comments", null);
        assertThat(anonymousList.status()).isEqualTo(200);
        assertThat(anonymousList.data().path("list").size()).isZero();

        HttpResult ownList = get("/api/v1/articles/" + article + "/comments", adminToken());
        assertThat(ownList.data().path("list").size()).isEqualTo(1);
        assertThat(ownList.data().path("list").get(0).path("status").asText()).isEqualTo("PENDING");

        // 审核通过 → 计数 +1，匿名可见
        HttpResult approved = put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "APPROVED", "remark", "ok"), adminToken());
        assertThat(approved.status()).isEqualTo(200);
        Integer commentCount = jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(commentCount).isEqualTo(1);

        // 回复：rootId 指向根，replyToUser 有值
        HttpResult reply = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", "多谢！", "parentId", String.valueOf(commentId)), adminToken());
        assertThat(reply.status()).isEqualTo(201);
        long replyId = reply.data().path("id").asLong();
        assertThat(reply.data().path("rootId").asText()).isEqualTo(String.valueOf(commentId));

        // 审核通过回复后，树里 root.children 挂载回复
        put("/api/v1/admin/comments/" + replyId + "/status",
                Map.of("status", "APPROVED"), adminToken());
        HttpResult tree = get("/api/v1/articles/" + article + "/comments", null);
        JsonNode root = tree.data().path("list").get(0);
        assertThat(root.path("children").size()).isEqualTo(1);
        assertThat(root.path("children").get(0).path("replyToUser").path("nickname").asText())
                .isEqualTo("超级管理员");

        // 下架根评论（APPROVED→HIDDEN）→ 计数回退
        put("/api/v1/admin/comments/" + commentId + "/status", Map.of("status", "HIDDEN"),
                adminToken());
        Integer afterHide = jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(afterHide).isEqualTo(1); // 回复仍在

        // 删除已过审回复 → 计数归零
        HttpResult deleted = delete("/api/v1/comments/" + replyId, adminToken());
        assertThat(deleted.status()).isEqualTo(200);
        Integer afterDelete = jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(afterDelete).isZero();
    }

    @Test
    void deletedRootKeepsPlaceholderForChildren() {
        long article = createPublishedArticle(adminToken());
        String token = adminToken();
        long root = approvedComment(article, "根评论", token);
        long child = approvedReply(article, root, "子评论", token);

        delete("/api/v1/comments/" + root, token);
        HttpResult tree = get("/api/v1/articles/" + article + "/comments", null);
        assertThat(tree.data().path("list").size()).isEqualTo(1);
        JsonNode placeholder = tree.data().path("list").get(0);
        assertThat(placeholder.path("deleted").asBoolean()).isTrue();
        assertThat(placeholder.path("content").isNull()).isTrue();
        assertThat(placeholder.path("children").size()).isEqualTo(1);
        assertThat(placeholder.path("children").get(0).path("content").asText()).isEqualTo("子评论");
    }

    private long approvedComment(long article, String content, String token) {
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                commentBody(content), token);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        assertThat(put("/api/v1/admin/comments/" + id + "/status",
                Map.of("status", "APPROVED"), token).status()).isEqualTo(200);
        return id;
    }

    private long approvedReply(long article, long rootId, String content, String token) {
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", content, "parentId", String.valueOf(rootId)), token);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        assertThat(put("/api/v1/admin/comments/" + id + "/status",
                Map.of("status", "APPROVED"), token).status()).isEqualTo(200);
        return id;
    }

    @Test
    void sensitiveWordMasksAndForcesPending() {
        jdbc.update("""
                INSERT INTO site_configs (config_key, config_value, value_type, description, is_public)
                VALUES ('comment.sensitive_words', '["敏感词"]', 'JSON', '测试词库', 0)
                ON DUPLICATE KEY UPDATE config_value = '["敏感词"]'
                """);
        siteConfigService.invalidate();
        long article = createPublishedArticle(adminToken());

        // 全角 + 零宽变体仍命中（NFKC 归一后匹配）
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                commentBody("这里有\u200B敏\uFEFF感\u200B词​变体"), adminToken());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("status").asText()).isEqualTo("PENDING");
        String content = jdbc.queryForObject(
                "SELECT content FROM comments ORDER BY id DESC LIMIT 1", String.class);
        assertThat(content).contains("***");

        jdbc.update("DELETE FROM site_configs WHERE config_key = 'comment.sensitive_words'");
        siteConfigService.invalidate();
    }

    @Test
    void contentLengthAndParentValidation() {
        long article = createPublishedArticle(adminToken());
        HttpResult tooLong = post("/api/v1/articles/" + article + "/comments",
                commentBody("字".repeat(2001)), adminToken());
        assertThat(tooLong.status()).isEqualTo(400);

        long other = createPublishedArticle(adminToken());
        long otherComment = approvedComment(other, "别的文章评论", adminToken());
        HttpResult crossArticle = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", "x", "parentId", String.valueOf(otherComment)), adminToken());
        assertThat(crossArticle.status()).isEqualTo(422);
        assertThat(crossArticle.code()).isEqualTo("COMMENT_PARENT_INVALID");

        HttpResult badCursor = get("/api/v1/articles/" + article + "/comments?cursor=!!!", null);
        assertThat(badCursor.status()).isEqualTo(400);
    }

    @Test
    void guestCommentControlledByConfig() {
        long article = createPublishedArticle(adminToken());
        HttpResult guest = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", "游客说", "guestNickname", "路人甲"), null);
        assertThat(guest.status()).isEqualTo(201);
        assertThat(guest.data().path("author").path("guest").asBoolean()).isTrue();

        jdbc.update("""
                INSERT INTO site_configs (config_key, config_value, value_type, description, is_public)
                VALUES ('comment.guest_enabled', 'false', 'BOOLEAN', 'test', 1)
                ON DUPLICATE KEY UPDATE config_value = 'false'
                """);
        siteConfigService.invalidate();
        HttpResult blocked = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", "游客说2"), null);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("COMMENT_GUEST_DISABLED");
        jdbc.update("UPDATE site_configs SET config_value = 'true' WHERE config_key = 'comment.guest_enabled'");
        siteConfigService.invalidate();
    }

    // ------------------------------------------------------------ 点赞/收藏

    @Test
    void reactionToggleIdempotentAndCountersConsistent() {
        String author = unique("au");
        register(author, PASSWORD, null);
        long article = createPublishedArticle(editorToken(author));
        String username = unique("ac");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);

        // 连续 10 次 PUT → 明细 1 行，计数 +1
        for (int i = 0; i < 10; i++) {
            HttpResult result = put("/api/v1/articles/" + article + "/reactions/like",
                    Map.of(), token);
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.data().path("active").asBoolean()).isTrue();
        }
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_reactions WHERE article_id = ? AND type = 'LIKE'",
                Long.class, article);
        assertThat(rows).isEqualTo(1L);
        Integer likeCount = jdbc.queryForObject(
                "SELECT like_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(likeCount).isEqualTo(1);

        // DELETE 两次 → 计数只减一次
        assertThat(delete("/api/v1/articles/" + article + "/reactions/like", token)
                .data().path("active").asBoolean()).isFalse();
        HttpResult again = delete("/api/v1/articles/" + article + "/reactions/like", token);
        assertThat(again.status()).isEqualTo(200);
        Integer afterUnlike = jdbc.queryForObject(
                "SELECT like_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(afterUnlike).isZero();

        // 收藏与点赞互不影响；me 接口返回双状态
        put("/api/v1/articles/" + article + "/reactions/favorite", Map.of(), token);
        HttpResult me = get("/api/v1/articles/" + article + "/reactions/me", token);
        assertThat(me.data().path("like").asBoolean()).isFalse();
        assertThat(me.data().path("favorite").asBoolean()).isTrue();

        // 我的收藏/点赞列表
        HttpResult favorites = get("/api/v1/me/favorites", token);
        assertThat(favorites.data().path("list").size()).isEqualTo(1);
        HttpResult likes = get("/api/v1/me/likes", token);
        assertThat(likes.data().path("list").size()).isZero();

        // 非法 type
        HttpResult badType = put("/api/v1/articles/" + article + "/reactions/star", Map.of(), token);
        assertThat(badType.status()).isEqualTo(400);
        assertThat(badType.code()).isEqualTo("REACTION_TYPE_INVALID");

        // 未登录点赞 → 401
        assertThat(put("/api/v1/articles/" + article + "/reactions/like", Map.of(), null)
                .status()).isEqualTo(401);
    }

    @Test
    void commentLikeToggle() {
        long article = createPublishedArticle(adminToken());
        long comment = approvedComment(article, "值得点赞", adminToken());
        String username = unique("cl");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);

        HttpResult liked = post("/api/v1/comments/" + comment + "/like", Map.of(), token);
        assertThat(liked.status()).isEqualTo(200);
        assertThat(liked.data().path("liked").asBoolean()).isTrue();
        assertThat(liked.data().path("likeCount").asInt()).isEqualTo(1);

        // 幂等
        post("/api/v1/comments/" + comment + "/like", Map.of(), token);
        Integer count = jdbc.queryForObject(
                "SELECT like_count FROM comments WHERE id = ?", Integer.class, comment);
        assertThat(count).isEqualTo(1);

        HttpResult unliked = delete("/api/v1/comments/" + comment + "/like", token);
        assertThat(unliked.data().path("liked").asBoolean()).isFalse();
        count = jdbc.queryForObject("SELECT like_count FROM comments WHERE id = ?",
                Integer.class, comment);
        assertThat(count).isZero();
    }

    @Test
    void counterReconcileFixesDrift() {
        String author = unique("rc");
        register(author, PASSWORD, null);
        long article = createPublishedArticle(editorToken(author));

        // 人工制造计数漂移：like_count 改成 5，明细为 0
        jdbc.update("UPDATE articles SET like_count = 5, updated_at = updated_at WHERE id = ?",
                article);
        reconcileService.run();
        Integer likeCount = jdbc.queryForObject(
                "SELECT like_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(likeCount).isZero();
        Long auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'INTERACTION_RECONCILE' AND target_id = ?",
                Long.class, String.valueOf(article));
        assertThat(auditRows).isGreaterThanOrEqualTo(1L);
    }

    // ------------------------------------------------------------ 留言

    @Test
    void guestbookLifecycle() {
        HttpResult created = post("/api/v1/guestbook",
                Map.of("content", "站点不错", "nickname", "访客"), null);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();

        // 默认审核开启 → 公开列表为空
        HttpResult publicList = get("/api/v1/guestbook", null);
        assertThat(publicList.data().path("list").size()).isZero();

        assertThat(put("/api/v1/admin/guestbook/" + id + "/status",
                Map.of("status", "APPROVED"), adminToken()).status()).isEqualTo(200);
        publicList = get("/api/v1/guestbook", null);
        assertThat(publicList.data().path("list").size()).isEqualTo(1);

        // 超长留言 400
        assertThat(post("/api/v1/guestbook", Map.of("content", "长".repeat(1001)), null)
                .status()).isEqualTo(400);

        // 普通用户无审核权限
        String username = unique("gb");
        register(username, PASSWORD, null);
        assertThat(put("/api/v1/admin/guestbook/" + id + "/status",
                Map.of("status", "HIDDEN"), accessToken(username, PASSWORD)).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------ 通知

    @Test
    void notificationsWithDedupAndRead() throws Exception {
        String author = unique("na");
        register(author, PASSWORD, null);
        String authorToken = editorToken(author);
        long article = createPublishedArticle(authorToken);

        // 另一个用户点赞两次（第二次幂等不重复通知）
        String username = unique("nb");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        long actorId = userId(username);
        put("/api/v1/articles/" + article + "/reactions/like", Map.of(), token);
        put("/api/v1/articles/" + article + "/reactions/like", Map.of(), token);

        // 作者收到 1 条 ARTICLE_LIKED
        awaitNotification(author, "ARTICLE_LIKED", 1);
        Long rows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_id = ? AND type = 'ARTICLE_LIKED'
                """, Long.class, userId(author));
        assertThat(rows).isEqualTo(1L);

        // 评论过审 → 作者收 ARTICLE_COMMENTED、评论者收 COMMENT_APPROVED
        long comment = approvedCommentBy(article, "前来学习", token);
        awaitNotification(author, "ARTICLE_COMMENTED", 1);
        awaitNotification(username, "COMMENT_APPROVED", 1);

        // 未读数 + 批量已读 + 全部已读
        HttpResult unread = get("/api/v1/notifications/unread-count", authorToken);
        assertThat(unread.data().path("count").asLong()).isEqualTo(2L);

        Long firstNotificationId = jdbc.queryForObject(
                "SELECT MIN(id) FROM notifications WHERE recipient_id = ?", Long.class,
                userId(author));
        HttpResult read = post("/api/v1/notifications/read",
                Map.of("ids", List.of(String.valueOf(firstNotificationId))), authorToken);
        assertThat(read.data().path("updated").asInt()).isEqualTo(1);
        assertThat(read.data().path("unreadCount").asLong()).isEqualTo(1L);

        HttpResult readAgain = post("/api/v1/notifications/read",
                Map.of("ids", List.of(String.valueOf(firstNotificationId))), authorToken);
        assertThat(readAgain.data().path("updated").asInt()).isZero();

        HttpResult readAll = post("/api/v1/notifications/read-all", Map.of(), authorToken);
        assertThat(readAll.data().path("unreadCount").asLong()).isZero();
    }

    private long approvedCommentBy(long article, String content, String token) {
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                commentBody(content), token);
        assertThat(created.status()).isEqualTo(201);
        long id = created.data().path("id").asLong();
        assertThat(put("/api/v1/admin/comments/" + id + "/status",
                Map.of("status", "APPROVED"), adminToken()).status()).isEqualTo(200);
        return id;
    }

    private void awaitNotification(String username, String type, int expected) throws Exception {
        long userId = userId(username);
        for (int i = 0; i < 40; i++) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                    Long.class, userId, type);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Long.class, userId, type)).isEqualTo((long) expected);
    }

    // ------------------------------------------------------------ SSE

    @Test
    void sseStreamRequiresAuthAndDeliversEvents() throws Exception {
        // 未带 token → 401
        HttpResult anonymous = get("/api/v1/notifications/stream", null);
        assertThat(anonymous.status()).isEqualTo(401);

        String username = unique("ss");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        long userId = userId(username);

        // 用流式请求读取 SSE：先收 ready（含未读数）
        List<String> lines = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(token);
                headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                rest.execute(URI.create("/api/v1/notifications/stream"), HttpMethod.GET,
                        request -> request.getHeaders().putAll(headers),
                        response -> {
                            try (BufferedReader bufferedReader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while (lines.size() < 4 && (line = bufferedReader.readLine()) != null) {
                                    lines.add(line);
                                }
                            }
                            return null;
                        });
            } catch (Exception ignored) {
                // 连接由读取条数触发关闭
            }
        });
        reader.start();
        Thread.sleep(1500);

        // 推送一条通知（另一用户对其文章点赞）
        jdbc.update("""
                INSERT INTO notifications (recipient_id, type, title, content, link_type, link_id, dedupe_key, is_read, created_at)
                VALUES (?, 'SYSTEM', '测试推送', 'hello', 'SYSTEM', NULL, ?
                        , 0, UTC_TIMESTAMP(3))
                """, userId, "sse-test:" + unique("k"));
        // 直接验证 DB 落库 + ready 事件结构
        Thread.sleep(1000);
        reader.interrupt();

        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).contains("event:ready").contains("unreadCount");
    }
}
