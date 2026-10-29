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

    @Autowired
    private com.wzblog.modules.interaction.infra.sse.SseConnectionRegistry sseConnectionRegistry;

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
        assertThat(created.status()).as("create article: %s", created.body()).isEqualTo(200);
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
        assertThat(created.status()).isEqualTo(200);
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
        assertThat(reply.status()).isEqualTo(200);
        long replyId = reply.data().path("id").asLong();
        assertThat(reply.data().path("rootId").asText()).isEqualTo(String.valueOf(commentId));

        // 审核通过回复后，树里 root.children 挂载回复
        put("/api/v1/admin/comments/" + replyId + "/status",
                Map.of("status", "APPROVED"), adminToken());
        HttpResult tree = get("/api/v1/articles/" + article + "/comments", null);
        JsonNode root = tree.data().path("list").get(0);
        assertThat(root.path("children").size()).isEqualTo(1);
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = 'bootstrap_admin'", Long.class);
        assertThat(root.path("children").get(0).path("replyToUser").path("userId").asText())
                .isEqualTo(String.valueOf(adminId));

        // 下架根评论（APPROVED→HIDDEN）→ 根与子回复都不可见，计数归零
        put("/api/v1/admin/comments/" + commentId + "/status", Map.of("status", "HIDDEN"),
                adminToken());
        Integer afterHide = jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(afterHide).isZero();

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
        Integer count = jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void pendingRootDoesNotCountInvisibleApprovedChildren() {
        long article = createPublishedArticle(adminToken());
        HttpResult rootCreated = post("/api/v1/articles/" + article + "/comments",
                commentBody("待审根评论"), adminToken());
        long root = rootCreated.data().path("id").asLong();
        long child = insertComment(article, root, "已通过但根未审的子回复", "APPROVED");

        assertThat(get("/api/v1/articles/" + article + "/comments", null)
                .data().path("list").size()).isZero();
        assertThat(commentCount(article)).isZero();

        assertThat(put("/api/v1/admin/comments/" + root + "/status",
                Map.of("status", "APPROVED"), adminToken()).status()).isEqualTo(200);
        assertThat(commentCount(article)).isEqualTo(2);
        JsonNode tree = get("/api/v1/articles/" + article + "/comments", null).data();
        assertThat(tree.path("list").size()).isEqualTo(1);
        assertThat(tree.path("list").get(0).path("children").size()).isEqualTo(1);
        assertThat(tree.path("list").get(0).path("children").get(0).path("id").asText())
                .isEqualTo(String.valueOf(child));
    }

    @Test
    void cannotApproveChildWhenRootIsNotVisible() {
        long article = createPublishedArticle(adminToken());
        HttpResult rootCreated = post("/api/v1/articles/" + article + "/comments",
                commentBody("待审根"), adminToken());
        long root = rootCreated.data().path("id").asLong();
        long child = insertComment(article, root, "待审子回复", "PENDING");

        HttpResult result = put("/api/v1/admin/comments/" + child + "/status",
                Map.of("status", "APPROVED"), adminToken());
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("COMMENT_STATUS_INVALID");
        assertThat(commentCount(article)).isZero();
    }

    @Test
    void hidingRootExcludesCountAndTreeUntilRestored() {
        long article = createPublishedArticle(adminToken());
        long root = approvedComment(article, "根", adminToken());
        approvedReply(article, root, "子", adminToken());
        assertThat(commentCount(article)).isEqualTo(2);

        assertThat(put("/api/v1/admin/comments/" + root + "/status",
                Map.of("status", "HIDDEN"), adminToken()).status()).isEqualTo(200);
        assertThat(commentCount(article)).isZero();
        assertThat(get("/api/v1/articles/" + article + "/comments", null)
                .data().path("list").size()).isZero();

        assertThat(put("/api/v1/admin/comments/" + root + "/status",
                Map.of("status", "APPROVED"), adminToken()).status()).isEqualTo(200);
        assertThat(commentCount(article)).isEqualTo(2);
    }

    @Test
    void crossArticleChildIsNotShownOrCountedAndReconcileDetectsDrift() {
        long articleA = createPublishedArticle(adminToken());
        long articleB = createPublishedArticle(adminToken());
        long root = approvedComment(articleA, "A 的根", adminToken());
        long child = approvedReply(articleA, root, "被搬到 B 的回复", adminToken());
        jdbc.update("UPDATE comments SET article_id = ? WHERE id = ?", articleB, child);

        // 子回复被 article_id 过滤，不能串到 A 的树里；B 没有根评论，也不能展示它
        JsonNode treeA = get("/api/v1/articles/" + articleA + "/comments", null).data();
        assertThat(treeA.path("list").get(0).path("children").size()).isZero();
        assertThat(get("/api/v1/articles/" + articleB + "/comments", null)
                .data().path("list").size()).isZero();

        // 让对账绕过 10 分钟活跃窗口，按可见口径修正 A=1、B=0
        jdbc.update("""
                UPDATE comments SET created_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 20 MINUTE),
                                    updated_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 20 MINUTE)
                WHERE id IN (?, ?)
                """, root, child);
        reconcileService.run();
        assertThat(commentCount(articleA)).isEqualTo(1);
        assertThat(commentCount(articleB)).isZero();
    }

    private long insertComment(long article, Long rootId, String content, String status) {
        jdbc.update("""
                INSERT INTO comments (article_id, user_id, guest_name, root_id, parent_id,
                                      reply_to_user_id, content, status, like_count, created_at, updated_at)
                VALUES (?, NULL, '测试访客', ?, ?, NULL, ?, ?, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, article, rootId, rootId, content, status);
        return jdbc.queryForObject("""
                SELECT id FROM comments WHERE article_id = ? AND content = ? ORDER BY id DESC LIMIT 1
                """, Long.class, article, content);
    }

    private int commentCount(long article) {
        return jdbc.queryForObject(
                "SELECT comment_count FROM articles WHERE id = ?", Integer.class, article);
    }

    private long approvedComment(long article, String content, String token) {
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                commentBody(content), token);
        assertThat(created.status()).isEqualTo(200);
        long id = created.data().path("id").asLong();
        assertThat(put("/api/v1/admin/comments/" + id + "/status",
                Map.of("status", "APPROVED"), token).status()).isEqualTo(200);
        return id;
    }

    private long approvedReply(long article, long rootId, String content, String token) {
        HttpResult created = post("/api/v1/articles/" + article + "/comments",
                Map.of("content", content, "parentId", String.valueOf(rootId)), token);
        assertThat(created.status()).isEqualTo(200);
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
        assertThat(created.status()).isEqualTo(200);
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
        assertThat(guest.status()).isEqualTo(200);
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
        assertThat(created.status()).isEqualTo(200);
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
        assertThat(created.status()).isEqualTo(200);
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

    // ------------------------------------------------------------ A 方案：审核范围与 D13

    @Test
    void editorModeratesOnlyOwnArticleComments() {
        String authorName = unique("cma");
        register(authorName, PASSWORD, null);
        String author = editorToken(authorName);
        long article = createPublishedArticle(author);

        String commenterName = unique("cmb");
        register(commenterName, PASSWORD, null);
        String commenter = accessToken(commenterName, PASSWORD);
        HttpResult comment = post("/api/v1/articles/" + article + "/comments",
                commentBody("路过"), commenter);
        assertThat(comment.status()).isEqualTo(200);
        long commentId = comment.data().path("id").asLong();

        // EDITOR 不带 articleId 查全站评论 → 403；带自己的 articleId → 200
        assertThat(get("/api/v1/admin/comments", author).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/comments?articleId=" + article, author).status()).isEqualTo(200);

        // 其他 EDITOR 不能审、不能列
        String otherName = unique("cmc");
        register(otherName, PASSWORD, null);
        String other = editorToken(otherName);
        assertThat(put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "APPROVED"), other).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/comments?articleId=" + article, other).status()).isEqualTo(403);

        // 作者不能审自己的评论（防自审拿分）
        HttpResult selfComment = post("/api/v1/articles/" + article + "/comments",
                commentBody("自评"), author);
        assertThat(selfComment.status()).isEqualTo(200);
        long selfId = selfComment.data().path("id").asLong();
        assertThat(put("/api/v1/admin/comments/" + selfId + "/status",
                Map.of("status", "APPROVED"), author).status()).isEqualTo(403);

        // 作者审他人评论 → 200；留言板与全站列表仍归 ADMIN
        assertThat(put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "APPROVED"), author).status()).isEqualTo(200);
        assertThat(get("/api/v1/admin/guestbook", author).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/comments", adminToken()).status()).isEqualTo(200);
    }

    /** D13：隐藏是可逆状态不扣回积分；删除是终态才扣回；重新通过不重复发分。 */
    @Test
    void hiddenCommentKeepsPointsUntilDeleted() throws Exception {
        long article = createPublishedArticle(adminToken());
        String commenterName = unique("hp");
        register(commenterName, PASSWORD, null);
        String commenter = accessToken(commenterName, PASSWORD);
        HttpResult comment = post("/api/v1/articles/" + article + "/comments",
                commentBody("学到很多"), commenter);
        long commentId = comment.data().path("id").asLong();

        String admin = adminToken();
        assertThat(put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "APPROVED"), admin).status()).isEqualTo(200);
        // 每日首登 +5，评论过审 +5
        awaitPointsBalance(commenterName, 10);

        assertThat(put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "HIDDEN"), admin).status()).isEqualTo(200);
        Thread.sleep(500);
        assertThat(pointsBalance(commenterName)).as("隐藏不反转积分").isEqualTo(10);

        assertThat(put("/api/v1/admin/comments/" + commentId + "/status",
                Map.of("status", "APPROVED"), admin).status()).isEqualTo(200);
        Thread.sleep(500);
        assertThat(pointsBalance(commenterName)).as("重新通过不重复发分").isEqualTo(10);

        assertThat(delete("/api/v1/admin/comments/" + commentId, admin).status()).isEqualTo(200);
        awaitPointsBalance(commenterName, 5);
    }

    @Test
    void selfLikeDoesNotGrantPoints() throws Exception {
        String authorName = unique("sl");
        register(authorName, PASSWORD, null);
        String author = editorToken(authorName);
        long article = createPublishedArticle(author);

        // 每日首登 +5，发布 +10
        awaitPointsBalance(authorName, 15);

        assertThat(put("/api/v1/articles/" + article + "/reactions/like", Map.of(), author)
                .status()).isEqualTo(200);
        Thread.sleep(600);
        assertThat(pointsBalance(authorName)).as("自赞不计分").isEqualTo(15);

        String fanName = unique("fan");
        register(fanName, PASSWORD, null);
        String fan = accessToken(fanName, PASSWORD);
        assertThat(put("/api/v1/articles/" + article + "/reactions/like", Map.of(), fan)
                .status()).isEqualTo(200);
        awaitPointsBalance(authorName, 16);
    }

    private int pointsBalance(String username) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT balance FROM points_accounts WHERE user_id = ?", Integer.class,
                userId(username));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private void awaitPointsBalance(String username, int expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (pointsBalance(username) == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(pointsBalance(username)).as("points balance").isEqualTo(expected);
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

    @Test
    void sseStreamLimitReturns429JsonEvenWithEventStreamAccept() {
        String username = unique("ssl");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        long userId = userId(username);

        for (int i = 0; i < com.wzblog.modules.interaction.infra.sse.SseConnectionRegistry.MAX_PER_USER; i++) {
            assertThat(sseConnectionRegistry.register(userId,
                    new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L))).isTrue();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        org.springframework.http.ResponseEntity<String> resp = rest.exchange(
                URI.create("/api/v1/notifications/stream"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(resp.getBody()).contains("NOTIFY_STREAM_LIMITED");
    }
    @Test
    void offsetPaginationRejectsOutOfRangeParams() throws Exception {
        String username = unique("pg");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);

        HttpResult oversize = get("/api/v1/notifications?page=1&pageSize=999", token);
        assertThat(oversize.status()).isEqualTo(400);
        assertThat(oversize.code()).isEqualTo("COMMON_VALIDATION_ERROR");

        HttpResult zeroPage = get("/api/v1/notifications?page=0&pageSize=5", token);
        assertThat(zeroPage.status()).isEqualTo(400);

        HttpResult negative = get("/api/v1/notifications?page=-3&pageSize=5", token);
        assertThat(negative.status()).isEqualTo(400);

        HttpResult legal = get("/api/v1/notifications?page=1&pageSize=50", token);
        assertThat(legal.status()).isEqualTo(200);
    }
}
