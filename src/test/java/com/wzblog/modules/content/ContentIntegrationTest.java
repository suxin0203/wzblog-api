package com.wzblog.modules.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.modules.content.app.ArticleCounterService;
import com.wzblog.modules.content.app.ArticleStatsService;
import com.wzblog.modules.content.app.CounterType;
import com.wzblog.modules.content.app.port.DailyViews;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 内容模块集成测试：写入流水线、可见性、修订、搜索、阅读计数、分类标签与端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ContentIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private ArticleCounterService counterService;

    @Autowired
    private ArticleStatsService statsService;

    // ------------------------------------------------------------ 写入

    @Test
    void createGeneratesSlugRevisionAndDerivedColumns() {
        String token = adminToken();
        String title = unique("hello");
        HttpResult created = createArticle(token, articleBody(title, "# Hi\n\nMarkdown **body**."));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.code()).isEqualTo("OK");
        assertThat(created.data().path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.data().path("version").asInt()).isZero();
        String slug = created.data().path("slug").asText();
        assertThat(slug).isEqualTo(title.toLowerCase());
        long id = created.data().path("id").asLong();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions WHERE article_id = ?",
                Long.class, id)).isEqualTo(1L);
        String html = jdbc.queryForObject("SELECT content_html FROM articles WHERE id = ?", String.class, id);
        assertThat(html).contains("<h1").contains("<strong>body</strong>");
        assertThat(jdbc.queryForObject("SELECT word_count FROM articles WHERE id = ?", Integer.class, id))
                .isGreaterThan(0);
    }

    @Test
    void duplicateSlugAndChineseTitleFallback() {
        String token = adminToken();
        String slug = unique("slug");
        HttpResult first = createArticle(token, articleBody(unique("dup"), "body", Map.of("slug", slug)));
        assertThat(first.status()).isEqualTo(200);

        HttpResult second = createArticle(token, articleBody(unique("dup2"), "body", Map.of("slug", slug)));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("ARTICLE_SLUG_CONFLICT");

        HttpResult chinese = createArticle(token, articleBody("中文标题", "正文"));
        assertThat(chinese.status()).isEqualTo(200);
        assertThat(chinese.data().path("slug").asText()).matches("^post-[a-z0-9]{8}$");
    }

    @Test
    void contentOverLimitReturnsValidationError() {
        String token = adminToken();
        String oversized = "a".repeat(204801);
        HttpResult result = createArticle(token, articleBody(unique("big"), oversized));
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.error("contentMd")).isNotBlank();
    }

    @Test
    void marksdownIsSanitizedAndExternalImageRejected() {
        String token = adminToken();
        String content = """
                # Safe

                <script>alert(1)</script>

                <img src=x onerror=alert(1)>

                [link](javascript:alert(1))
                """;
        HttpResult created = createArticle(token, articleBody(unique("safe"), content));
        assertThat(created.status()).isEqualTo(200);
        publish(created.data().path("id").asText(), token);

        HttpResult detail = get("/api/v1/articles/" + created.data().path("slug").asText(), null);
        assertThat(detail.status()).isEqualTo(200);
        String html = detail.data().path("html").asText();
        assertThat(html).doesNotContain("<script").doesNotContain("<img src=x")
                .doesNotContain("href=\"javascript:");
        JsonNode toc = detail.data().path("toc");
        assertThat(toc.isArray()).isTrue();
        assertThat(toc.get(0).path("anchor").asText()).isEqualTo("safe");

        HttpResult rejected = createArticle(token,
                articleBody(unique("img"), "![x](https://evil.example/a.png)"));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("ARTICLE_IMAGE_INVALID");

        // 正文引用 /media/ 图片需真实 files 行（M5 claimByKey 生效）
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', 'articles/2026/10/demo.png', ?, 'CONTENT_IMAGE', 'ACTIVE', 0, 100)
                """, userId("bootstrap_admin"));
        HttpResult local = createArticle(token,
                articleBody(unique("imgok"), "![x](/media/articles/2026/10/demo.png)"));
        assertThat(local.status()).isEqualTo(200);
    }

    // ------------------------------------------------------------ 状态与可见性

    @Test
    void publishUnpublishRepublishKeepsFirstPublishedAt() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("pub"), "body"));
        long id = created.data().path("id").asLong();
        String slug = created.data().path("slug").asText();

        HttpResult published = post("/api/v1/articles/" + id + "/publish", null, token);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.data().path("status").asText()).isEqualTo("PUBLISHED");
        String firstPublishedAt = published.data().path("publishedAt").asText();
        assertThat(firstPublishedAt).isNotBlank();

        HttpResult again = post("/api/v1/articles/" + id + "/publish", null, token);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.data().path("publishedAt").asText()).isEqualTo(firstPublishedAt);

        HttpResult detail = get("/api/v1/articles/" + slug, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path("html").asText()).isNotBlank();
        assertThat(detail.data().has("contentMd")).isFalse();

        assertThat(post("/api/v1/articles/" + id + "/unpublish", null, token).status()).isEqualTo(200);
        assertThat(get("/api/v1/articles/" + slug, null).status()).isEqualTo(404);

        HttpResult republished = post("/api/v1/articles/" + id + "/publish", null, token);
        assertThat(republished.status()).isEqualTo(200);
        assertThat(republished.data().path("publishedAt").asText()).isEqualTo(firstPublishedAt);
        assertThat(get("/api/v1/articles/" + slug, null).status()).isEqualTo(200);
    }

    @Test
    void privateAndUnlistedVisibility() {
        String token = adminToken();
        String categorySlug = unique("cat");
        long categoryId = createCategory(token, unique("分类"), categorySlug);

        HttpResult privateArticle = createArticle(token, articleBody(unique("priv"), "body",
                Map.of("visibility", "PRIVATE", "categoryId", String.valueOf(categoryId))));
        assertThat(privateArticle.status()).isEqualTo(200);
        long privateId = privateArticle.data().path("id").asLong();
        publish(String.valueOf(privateId), token);
        String privateSlug = privateArticle.data().path("slug").asText();
        assertThat(get("/api/v1/articles/" + privateSlug, null).status()).isEqualTo(404);
        assertThat(get("/api/v1/articles/" + privateSlug, token).status()).isEqualTo(200);

        HttpResult unlisted = createArticle(token, articleBody(unique("unlist"), "body",
                Map.of("visibility", "UNLISTED")));
        publish(unlisted.data().path("id").asText(), token);
        String unlistedSlug = unlisted.data().path("slug").asText();
        assertThat(get("/api/v1/articles/" + unlistedSlug, null).status()).isEqualTo(200);

        HttpResult feed = get("/api/v1/articles?categoryId=" + categoryId + "&size=50", null);
        assertThat(feed.status()).isEqualTo(200);
        for (JsonNode item : feed.data().path("list")) {
            assertThat(item.path("id").asLong()).isNotEqualTo(privateId);
        }

        assertThat(get("/api/v1/articles", null).status()).isEqualTo(200);
        assertThat(get("/api/v1/articles/search?keyword=zz", null).status()).isEqualTo(200);
    }

    @Test
    void versionConflictAndSlugImmutableAfterPublish() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("ver"), "body"));
        long id = created.data().path("id").asLong();

        Map<String, Object> update = articleBody(unique("ver2"), "new body");
        update.put("version", 999);
        HttpResult conflict = put("/api/v1/articles/" + id, update, token);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("ARTICLE_VERSION_CONFLICT");

        HttpResult published = publish(String.valueOf(id), token);
        int version = published.data().path("version").asInt();
        Map<String, Object> slugChange = articleBody(unique("ver3"), "body");
        slugChange.put("version", version);
        slugChange.put("slug", unique("other"));
        HttpResult immutable = put("/api/v1/articles/" + id, slugChange, token);
        assertThat(immutable.status()).isEqualTo(409);
        assertThat(immutable.code()).isEqualTo("ARTICLE_SLUG_IMMUTABLE");
    }

    @Test
    void updateRecalculatesDerivedColumnsAndCreatesRevision() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("upd"), "# One\n\nfirst"));
        long id = created.data().path("id").asLong();
        int wordBefore = jdbc.queryForObject("SELECT word_count FROM articles WHERE id = ?", Integer.class, id);

        Map<String, Object> update = articleBody(unique("upd"), "# Two\n\nsecond paragraph with more words");
        update.put("version", 0);
        update.put("changeNote", "update body");
        HttpResult result = put("/api/v1/articles/" + id, update, token);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("version").asInt()).isEqualTo(1);
        int wordAfter = jdbc.queryForObject("SELECT word_count FROM articles WHERE id = ?", Integer.class, id);
        assertThat(wordAfter).isGreaterThan(wordBefore);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions WHERE article_id = ?",
                Long.class, id)).isEqualTo(2L);
        HttpResult revisions = get("/api/v1/articles/" + id + "/revisions", token);
        assertThat(revisions.status()).isEqualTo(200);
        assertThat(revisions.data()).hasSize(2);
    }

    @Test
    void editorCannotEditOthersArticle() {
        String admin = adminToken();
        HttpResult published = createArticle(admin, articleBody(unique("cross"), "# One"));
        long publishedId = published.data().path("id").asLong();
        publish(String.valueOf(publishedId), admin);

        HttpResult draft = createArticle(admin, articleBody(unique("crossd"), "body"));
        long draftId = draft.data().path("id").asLong();

        String editorName = unique("editor");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        String cover = insertFile(userId(editorName), "ARTICLE_COVER");

        // 已公开文章：越权 403
        HttpResult forbidden = put("/api/v1/articles/" + publishedId,
                articleBody(unique("cross2"), "# Two", Map.of("version", 1, "coverKey", cover)),
                editor);
        assertThat(forbidden.status()).as("edit others: %s", forbidden.body()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("ARTICLE_FORBIDDEN");
        assertThat(refCount(cover)).isZero();
        assertThat(get("/api/v1/articles/" + publishedId + "/edit", editor).status()).isEqualTo(403);

        // 草稿：按不存在处理，不泄露存在性
        HttpResult hidden = put("/api/v1/articles/" + draftId,
                articleBody(unique("crossd2"), "body", Map.of("version", 0)), editor);
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.code()).isEqualTo("ARTICLE_NOT_FOUND");
        assertThat(get("/api/v1/articles/" + draftId + "/edit", editor).status()).isEqualTo(404);
        assertThat(get("/api/v1/articles/" + draftId + "/revisions", editor).status()).isEqualTo(404);
    }

    @Test
    void adminTakeoverThenEditWithAuditAndNotification() throws Exception {
        String editorName = unique("take");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        HttpResult created = createArticle(editor, articleBody(unique("take"), "# One"));
        long id = created.data().path("id").asLong();
        long editorId = userId(editorName);

        String admin = adminToken();
        // 不接管不能改（草稿 → 404）
        assertThat(put("/api/v1/articles/" + id,
                articleBody(unique("take2"), "# Two", Map.of("version", 0)), admin)
                .status()).isEqualTo(404);

        HttpResult takeover = post("/api/v1/admin/articles/" + id + "/takeover",
                Map.of("reason", "作者失联"), admin);
        assertThat(takeover.status()).as("takeover: %s", takeover.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT author_id FROM articles WHERE id = ?", Long.class, id))
                .isEqualTo(userId("bootstrap_admin"));
        assertThat(auditCount("ARTICLE_TAKEOVER", String.valueOf(id))).isEqualTo(1L);
        awaitNotification(editorId, "SYSTEM", 1);

        // 接管后站主成为作者，可以挂自己上传的封面
        String cover = insertFile(userId("bootstrap_admin"), "ARTICLE_COVER");
        HttpResult updated = put("/api/v1/articles/" + id,
                articleBody(unique("take3"), "# Three", Map.of("version", 1, "coverKey", cover)),
                admin);
        assertThat(updated.status()).as("update after takeover: %s", updated.body()).isEqualTo(200);
        assertThat(refCount(cover)).isEqualTo(1);

        // 原作者失去编辑权
        assertThat(put("/api/v1/articles/" + id,
                articleBody(unique("take4"), "# Four", Map.of("version", 2)), editor)
                .status()).isEqualTo(404);
    }

    @Test
    void editorCannotReferenceFilesUploadedByAnotherUser() {
        String authorName = unique("owner");
        register(authorName, PASSWORD, null);
        String author = promoteToEditor(authorName);
        HttpResult created = createArticle(author, articleBody(unique("idor"), "body"));
        long id = created.data().path("id").asLong();

        String uploaderName = unique("uploader");
        register(uploaderName, PASSWORD, null);
        String foreignKey = insertFile(userId(uploaderName), "CONTENT_IMAGE");

        HttpResult rejected = put("/api/v1/articles/" + id,
                articleBody(unique("idor2"), "![x](/media/" + foreignKey + ")",
                        Map.of("version", 0)), author);
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.code()).isEqualTo("FILE_PRESIGN_FORBIDDEN");
        assertThat(refCount(foreignKey)).isZero();
    }

    /** D13：软删可逆——不释放图片、不反转积分；purge 才是终态。 */
    @Test
    void softDeleteKeepsImageRefsUntilPurge() throws Exception {
        String editorName = unique("edimg");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        long editorId = userId(editorName);
        String image = insertFile(editorId, "CONTENT_IMAGE");

        HttpResult created = createArticle(editor,
                articleBody(unique("restore"), "![x](/media/" + image + ")"));
        long id = created.data().path("id").asLong();
        assertThat(refCount(image)).isEqualTo(1);

        String admin = adminToken();
        assertThat(delete("/api/v1/articles/" + id, admin).status()).isEqualTo(200);
        assertThat(refCount(image)).as("软删不释放引用").isEqualTo(1);
        assertThat(auditCount("ARTICLE_DELETE", String.valueOf(id))).isEqualTo(1L);
        awaitNotification(editorId, "SYSTEM", 1);

        assertThat(post("/api/v1/admin/articles/" + id + "/restore", null, admin).status())
                .isEqualTo(200);
        assertThat(refCount(image)).as("恢复不需要重新 claim").isEqualTo(1);

        assertThat(delete("/api/v1/articles/" + id, admin).status()).isEqualTo(200);
        assertThat(delete("/api/v1/admin/articles/" + id + "/purge", admin).status()).isEqualTo(200);
        assertThat(refCount(image)).as("purge 才释放引用").isZero();
        assertThat(auditCount("ARTICLE_PURGE", String.valueOf(id))).isEqualTo(1L);
    }

    @Test
    void restoreRevisionReclaimsRemovedImage() {
        String editorName = unique("reved");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        String image = insertFile(userId(editorName), "CONTENT_IMAGE");

        HttpResult created = createArticle(editor, articleBody(unique("revimg"), "# One"));
        long id = created.data().path("id").asLong();

        HttpResult withImage = put("/api/v1/articles/" + id,
                articleBody(unique("revimg"), "![x](/media/" + image + ")",
                        Map.of("version", 0)), editor);
        assertThat(withImage.status()).as("add image: %s", withImage.body()).isEqualTo(200);
        assertThat(refCount(image)).isEqualTo(1);

        HttpResult removed = put("/api/v1/articles/" + id,
                articleBody(unique("revimg"), "no image", Map.of("version", 1)), editor);
        assertThat(removed.status()).as("remove image: %s", removed.body()).isEqualTo(200);
        assertThat(refCount(image)).isZero();

        HttpResult restored = post("/api/v1/articles/" + id + "/revisions/2/restore",
                Map.of("changeNote", "restore image"), editor);
        assertThat(restored.status()).as("restore revision: %s", restored.body()).isEqualTo(200);
        assertThat(restored.data().path("contentMd").asText()).contains(image);
        assertThat(refCount(image)).isEqualTo(1);
    }

    @Test
    void editorCannotPinManageTaxonomyOrRestore() {
        String editorName = unique("nopin");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        HttpResult created = createArticle(editor, articleBody(unique("pin"), "body"));
        long id = created.data().path("id").asLong();
        publish(String.valueOf(id), editor);

        assertThat(post("/api/v1/articles/" + id + "/pin", null, editor).status()).isEqualTo(403);
        assertThat(post("/api/v1/admin/categories",
                Map.of("name", unique("分类"), "slug", unique("cat")), editor).status()).isEqualTo(403);
        assertThat(post("/api/v1/admin/tags",
                Map.of("name", unique("标签"), "slug", unique("tag")), editor).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/articles", editor).status()).isEqualTo(403);

        // 作者删除自己的文章后不能自行恢复
        assertThat(delete("/api/v1/articles/" + id, editor).status()).isEqualTo(200);
        assertThat(post("/api/v1/admin/articles/" + id + "/restore", null, editor).status())
                .isEqualTo(403);
        assertThat(post("/api/v1/admin/articles/" + id + "/restore", null, adminToken()).status())
                .isEqualTo(200);
    }

    /** D9：接管后 purge，发布积分必须退给原作者，而不是接管的站主。 */
    @Test
    void purgeAfterTakeoverReversesPointsToOriginalAuthor() throws Exception {
        String editorName = unique("pts");
        register(editorName, PASSWORD, null);
        String editor = promoteToEditor(editorName);
        long editorId = userId(editorName);
        HttpResult created = createArticle(editor, articleBody(unique("pts"), "body"));
        long id = created.data().path("id").asLong();
        publish(String.valueOf(id), editor);
        // 每日首登 +5，发布 +10
        awaitPointsBalance(editorName, 15);

        String admin = adminToken();
        assertThat(post("/api/v1/admin/articles/" + id + "/takeover", Map.of(), admin).status())
                .isEqualTo(200);
        // 软删不反转（D13）
        assertThat(delete("/api/v1/articles/" + id, admin).status()).isEqualTo(200);
        Thread.sleep(400);
        assertThat(pointsBalance(editorName)).as("软删不反转积分").isEqualTo(15);

        assertThat(delete("/api/v1/admin/articles/" + id + "/purge", admin).status()).isEqualTo(200);
        awaitPointsBalance(editorName, 5);
        Long reversalRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM points_ledger
                WHERE user_id = ? AND type = 'ARTICLE_PUBLISH_REVERSAL' AND biz_id = ?
                """, Long.class, editorId, String.valueOf(id));
        assertThat(reversalRows).isEqualTo(1L);
    }

    @Test
    void revisionRestoreRendersAgainAndAppendsRevision() {
        String token = adminToken();
        String original = "# One\n\noriginal text";
        HttpResult created = createArticle(token, articleBody(unique("rev"), original));
        long id = created.data().path("id").asLong();

        Map<String, Object> update = articleBody(unique("rev"), "# Two\n\nchanged text");
        update.put("version", 0);
        assertThat(put("/api/v1/articles/" + id, update, token).status()).isEqualTo(200);

        HttpResult restored = post("/api/v1/articles/" + id + "/revisions/1/restore",
                Map.of("changeNote", "back to one"), token);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.data().path("contentMd").asText()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT content_md FROM articles WHERE id = ?", String.class, id))
                .isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_revisions WHERE article_id = ?",
                Long.class, id)).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                "SELECT change_note FROM article_revisions WHERE article_id = ? ORDER BY revision_no DESC LIMIT 1",
                String.class, id)).isEqualTo("back to one");

        HttpResult missing = post("/api/v1/articles/" + id + "/revisions/99/restore", Map.of(), token);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("ARTICLE_REVISION_NOT_FOUND");
    }

    @Test
    void softDeleteRestoreAndPurge() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("del"), "body"));
        long id = created.data().path("id").asLong();
        String slug = created.data().path("slug").asText();
        publish(String.valueOf(id), token);

        assertThat(delete("/api/v1/articles/" + id, token).status()).isEqualTo(200);
        assertThat(get("/api/v1/articles/" + slug, null).status()).isEqualTo(404);

        HttpResult trash = get("/api/v1/admin/articles?deleted=true&keyword=" + slug, token);
        assertThat(trash.status()).isEqualTo(200);
        assertThat(trash.data().path("total").asLong()).isGreaterThanOrEqualTo(1);

        assertThat(post("/api/v1/admin/articles/" + id + "/restore", null, token).status()).isEqualTo(200);
        assertThat(get("/api/v1/articles/" + slug, null).status()).isEqualTo(200);

        assertThat(delete("/api/v1/articles/" + id, token).status()).isEqualTo(200);
        assertThat(delete("/api/v1/admin/articles/" + id + "/purge", token).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE id = ?", Long.class, id)).isZero();

        HttpResult alive = createArticle(token, articleBody(unique("alive"), "body"));
        HttpResult purgeAlive = delete("/api/v1/admin/articles/" + alive.data().path("id").asText() + "/purge", token);
        assertThat(purgeAlive.status()).isEqualTo(409);
        assertThat(purgeAlive.code()).isEqualTo("ARTICLE_PURGE_NOT_ALLOWED");
    }

    @Test
    void pinRequiresPublishedAndIsIdempotent() {
        String token = adminToken();
        HttpResult draft = createArticle(token, articleBody(unique("pin"), "body"));
        long draftId = draft.data().path("id").asLong();
        HttpResult notAllowed = post("/api/v1/articles/" + draftId + "/pin", null, token);
        assertThat(notAllowed.status()).isEqualTo(409);
        assertThat(notAllowed.code()).isEqualTo("ARTICLE_PIN_NOT_ALLOWED");

        publish(String.valueOf(draftId), token);
        assertThat(post("/api/v1/articles/" + draftId + "/pin", null, token).status()).isEqualTo(200);
        assertThat(post("/api/v1/articles/" + draftId + "/pin", null, token).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT is_pinned FROM articles WHERE id = ?", Integer.class, draftId))
                .as("is_pinned after pin").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM articles WHERE id = ?", String.class, draftId))
                .as("status after publish").isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT visibility FROM articles WHERE id = ?", String.class, draftId))
                .as("visibility").isEqualTo("PUBLIC");
        assertThat(jdbc.queryForObject("SELECT published_at FROM articles WHERE id = ?",
                java.time.LocalDateTime.class, draftId)).as("published_at").isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM articles WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC'
                  AND deleted_at IS NULL AND published_at IS NOT NULL AND is_pinned = 1
                """, Long.class)).as("raw pinned count").isGreaterThanOrEqualTo(1L);
        HttpResult pinned = get("/api/v1/articles/pinned?limit=10", null);
        assertThat(pinned.status()).isEqualTo(200);
        assertThat(listIds(pinned.data())).contains(String.valueOf(draftId));

        assertThat(delete("/api/v1/articles/" + draftId + "/pin", token).status()).isEqualTo(200);
        assertThat(delete("/api/v1/articles/" + draftId + "/pin", token).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------ 读取

    @Test
    void searchUsesContainsSemanticsAndDegradesForShortKeyword() {
        String token = adminToken();
        String keyword = "橘子猫";
        String titleHit = keyword + unique("t");
        HttpResult titled = createArticle(token, articleBody(titleHit, "正文没有那个词"));
        publish(titled.data().path("id").asText(), token);

        HttpResult summaryHit = createArticle(token, articleBody(unique("s"), "正文",
                Map.of("summary", "摘要提到了" + keyword)));
        publish(summaryHit.data().path("id").asText(), token);

        HttpResult bodyOnly = createArticle(token, articleBody(unique("body"), "正文提到了" + keyword,
                Map.of("summary", "摘要不含关键词")));
        publish(bodyOnly.data().path("id").asText(), token);

        HttpResult result = get("/api/v1/articles/search?keyword=" + keyword + "&size=20", null);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("degraded").asBoolean()).isFalse();
        List<String> ids = listIds(result.data().path("list"));
        assertThat(ids).contains(titled.data().path("id").asText(), summaryHit.data().path("id").asText());
        assertThat(ids).doesNotContain(bodyOnly.data().path("id").asText());

        // 与信息流包含匹配结果一致（同关键词、同页大小）
        HttpResult feed = get("/api/v1/articles?keyword=" + keyword + "&size=20", null);
        assertThat(listIds(feed.data().path("list"))).isEqualTo(ids);

        HttpResult degraded = get("/api/v1/articles/search?keyword=" + keyword.substring(0, 1), null);
        assertThat(degraded.status()).isEqualTo(200);
        assertThat(degraded.data().path("degraded").asBoolean()).isTrue();
    }

    @Test
    void topAndArchiveQueriesAreReachable() {
        String token = adminToken();
        String id = createArticle(token, articleBody(unique("榜单"), "榜单正文")).data().path("id").asText();
        publish(id, token);
        jdbc.update("UPDATE articles SET view_count = 100000, view_internal_count = 70000, "
                + "view_external_count = 30000 WHERE id = ?", Long.valueOf(id));

        HttpResult top = get("/api/v1/articles/top?type=view&limit=10", null);
        assertThat(top.status()).as("top: " + top.body()).isEqualTo(200);
        assertThat(listIds(top.data())).contains(id);

        HttpResult archive = get("/api/v1/articles/archive", null);
        assertThat(archive.status()).as("archive: " + archive.body()).isEqualTo(200);
        assertThat(archive.data().isArray()).isTrue();
        assertThat(archive.data()).isNotEmpty();
    }

    @Test
    void cursorPaginationHasNoDuplicatesOrGaps() {
        String token = adminToken();
        long categoryId = createCategory(token, unique("翻页"), unique("page"));
        List<String> createdIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HttpResult created = createArticle(token, articleBody(unique("cursor"), "body",
                    Map.of("categoryId", String.valueOf(categoryId))));
            createdIds.add(created.data().path("id").asText());
            publish(created.data().path("id").asText(), token);
        }

        Set<String> seen = new LinkedHashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/articles?categoryId=" + categoryId + "&size=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResult page = get(path, null);
            assertThat(page.status()).isEqualTo(200);
            List<String> ids = listIds(page.data().path("list"));
            for (String id : ids) {
                assertThat(seen.add(id)).as("duplicate id across pages").isTrue();
            }
            cursor = page.data().path("nextCursor").isNull() ? null : page.data().path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(createdIds);
    }

    @Test
    void viewDedupeWritesDailyCountersAndDrainsOnce() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("view"), "body"));
        long id = created.data().path("id").asLong();
        publish(String.valueOf(id), token);

        String ua = "content-view-test";
        HttpResult first = post("/api/v1/articles/" + id + "/view",
                Map.of("source", "external"), null, ua);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data().path("counted").asBoolean()).isTrue();
        assertThat(first.data().path("viewCount").asInt()).isEqualTo(1);

        HttpResult second = post("/api/v1/articles/" + id + "/view",
                Map.of("source", "external"), null, ua);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.data().path("counted").asBoolean()).isFalse();
        assertThat(second.data().path("viewCount").asInt()).isEqualTo(1);

        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String articleKey = "content:views:" + day;
        String sourceKey = "content:views:source:" + day;
        assertThat(redis.opsForHash().get(articleKey, String.valueOf(id))).isEqualTo("1");
        assertThat(redis.opsForHash().get(sourceKey, "external")).isEqualTo("1");
        Long ttl = redis.getExpire(articleKey, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 90000L);

        DailyViews drained = statsService.drainDailyViews(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(drained.articleViews()).containsEntry(id, 1);
        assertThat(drained.external()).isGreaterThanOrEqualTo(1);
        DailyViews again = statsService.drainDailyViews(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(again.articleViews()).doesNotContainKey(id);
    }

    @Test
    void taxonomyCrudAndArticleCategoryTags() {
        String token = adminToken();
        String categorySlug = unique("tax");
        HttpResult category = post("/api/v1/admin/categories",
                Map.of("name", unique("分类"), "slug", categorySlug, "description", "desc"), token);
        assertThat(category.status()).isEqualTo(200);
        String categoryId = category.data().path("id").asText();

        HttpResult duplicate = post("/api/v1/admin/categories",
                Map.of("name", unique("重复"), "slug", categorySlug), token);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("CATEGORY_SLUG_CONFLICT");

        HttpResult publicList = get("/api/v1/categories", null);
        assertThat(publicList.status()).isEqualTo(200);
        assertThat(publicList.data().findValuesAsText("slug")).contains(categorySlug);

        HttpResult tag = post("/api/v1/admin/tags",
                Map.of("name", unique("标签"), "slug", unique("tag")), token);
        assertThat(tag.status()).isEqualTo(200);
        String tagId = tag.data().path("id").asText();

        HttpResult article = createArticle(token, articleBody(unique("taxart"), "body",
                Map.of("categoryId", categoryId, "tagIds", List.of(tagId))));
        assertThat(article.status()).isEqualTo(200);
        String slug = article.data().path("slug").asText();
        publish(article.data().path("id").asText(), token);
        HttpResult detail = get("/api/v1/articles/" + slug, null);
        assertThat(detail.data().path("category").path("id").asText()).isEqualTo(categoryId);
        assertThat(detail.data().path("tags").get(0).path("id").asText()).isEqualTo(tagId);

        HttpResult edit = get("/api/v1/articles/" + article.data().path("id").asText() + "/edit", token);
        assertThat(edit.data().path("tagIds").get(0).asText()).isEqualTo(tagId);

        assertThat(delete("/api/v1/admin/categories/" + categoryId, token).status()).isEqualTo(200);
        assertThat(get("/api/v1/categories/" + categorySlug, null).status()).isEqualTo(404);
        assertThat(delete("/api/v1/admin/tags/" + tagId, token).status()).isEqualTo(200);
    }

    @Test
    void userCanListOwnArticlesButCannotWrite() {
        String username = unique("reader");
        register(username, PASSWORD, username + "@test.local");
        String userToken = accessToken(username, PASSWORD);

        HttpResult write = createArticle(userToken, articleBody(unique("nope"), "body"));
        assertThat(write.status()).isEqualTo(403);

        HttpResult mine = get("/api/v1/me/articles", userToken);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.data().path("total").asLong()).isZero();

        HttpResult anonymous = get("/api/v1/me/articles", null);
        assertThat(anonymous.status()).isEqualTo(401);

        HttpResult adminMine = get("/api/v1/me/articles", adminToken());
        assertThat(adminMine.status()).as("ADMIN inherits USER: " + adminMine.body()).isEqualTo(200);
    }

    @Test
    void counterServiceNeverGoesNegative() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("counter"), "body"));
        long id = created.data().path("id").asLong();

        assertThat(counterService.increment(id, CounterType.LIKE, 1)).isEqualTo(1);
        assertThat(counterService.increment(id, CounterType.LIKE, 0)).isEqualTo(1);
        assertThat(counterService.decrement(id, CounterType.LIKE, 1)).isZero();
        assertThat(counterService.decrement(id, CounterType.LIKE, 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT like_count FROM articles WHERE id = ?", Integer.class, id)).isZero();
    }

    // ------------------------------------------------------------ 工具方法

    private Map<String, Object> articleBody(String title, String content) {
        return articleBody(title, content, Map.of());
    }

    private Map<String, Object> articleBody(String title, String content, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("contentMd", content);
        body.putAll(extra);
        return body;
    }

    private HttpResult createArticle(String token, Map<String, Object> body) {
        return post("/api/v1/articles", body, token);
    }

    /** 提权为 EDITOR 后返回其 token；文章写接口要求 EDITOR/ADMIN。 */
    private String promoteToEditor(String username) {
        Long id = userId(username);
        HttpResult updated = put("/api/v1/admin/users/" + id,
                Map.of("nickname", "editor" + id, "role", "EDITOR", "status", "ACTIVE"), adminToken());
        assertThat(updated.status()).as("promote: %s", updated.body()).isEqualTo(200);
        return accessToken(username, PASSWORD);
    }

    private String insertFile(Long uploaderId, String bizType) {
        String objectKey = "articles/2026/10/" + unique("img") + ".png";
        jdbc.update("""
                INSERT INTO files (bucket, object_key, uploader_id, biz_type, status, ref_count, size_bytes)
                VALUES ('local', ?, ?, ?, 'ACTIVE', 0, 100)
                """, objectKey, uploaderId, bizType);
        return objectKey;
    }

    private int refCount(String objectKey) {
        return jdbc.queryForObject("SELECT ref_count FROM files WHERE object_key = ?",
                Integer.class, objectKey);
    }

    private int pointsBalance(String username) {
        java.util.List<Integer> rows = jdbc.queryForList(
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

    private long auditCount(String action, String targetId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                Long.class, action, targetId);
        return count == null ? 0L : count;
    }

    /** 治理通知是 AFTER_COMMIT 异步写入，轮询等待。 */
    private void awaitNotification(Long recipientId, String type, long expected)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                    Long.class, recipientId, type);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND type = ?",
                Long.class, recipientId, type)).as("notification " + type).isGreaterThanOrEqualTo(expected);
    }

    private HttpResult publish(String id, String token) {
        HttpResult result = post("/api/v1/articles/" + id + "/publish", null, token);
        assertThat(result.status()).as("publish: " + result.body()).isEqualTo(200);
        return result;
    }

    private long createCategory(String token, String name, String slug) {
        HttpResult result = post("/api/v1/admin/categories", Map.of("name", name, "slug", slug), token);
        assertThat(result.status()).as("category: " + result.body()).isEqualTo(200);
        return result.data().path("id").asLong();
    }

    private List<String> listIds(JsonNode list) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : list) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }

    // ------------------------------------------------------------ 缓存一致性

    @Test
    void detailCacheInvalidatedByPinAndUpdate() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("cache"), "# 旧标题内容"));
        long id = created.data().path("id").asLong();
        String slug = created.data().path("slug").asText();
        assertThat(post("/api/v1/articles/" + id + "/publish", Map.of(), token)
                .status()).isEqualTo(200);

        // 第一次读：写热缓存
        HttpResult first = get("/api/v1/articles/" + slug, null);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data().path("isPinned").asBoolean()).isFalse();
        String cacheKeyPattern = "content:article:" + id + ":v*";
        assertThat(redis.keys(cacheKeyPattern)).isNotEmpty();

        // 置顶：pin 必须递增 version 轮换缓存键，读者立刻看到新置顶状态（回归：曾最长 10 分钟脏读）
        assertThat(post("/api/v1/articles/" + id + "/pin", Map.of(), token).status()).isEqualTo(200);
        HttpResult afterPin = get("/api/v1/articles/" + slug, null);
        assertThat(afterPin.data().path("isPinned").asBoolean())
                .as("pin 后详情不得返回缓存的旧置顶状态").isTrue();

        // 更新内容：同样立刻生效
        HttpResult editVo = get("/api/v1/articles/" + id + "/edit", token);
        int version = editVo.data().path("version").asInt();
        HttpResult updated = put("/api/v1/articles/" + id,
                articleBody(unique("cache2"), "# 新标题内容", Map.of("version", version)), token);
        assertThat(updated.status()).as("update: %s", updated.body()).isEqualTo(200);
        HttpResult afterUpdate = get("/api/v1/articles/" + slug, null);
        assertThat(afterUpdate.data().path("title").asText()).startsWith("cache2");
        assertThat(afterUpdate.data().path("html").asText()).contains("新标题内容");
    }

    @Test
    void detailCacheDoesNotFreezeCounters() {
        String token = adminToken();
        HttpResult created = createArticle(token, articleBody(unique("cnt"), "计数测试"));
        long id = created.data().path("id").asLong();
        String slug = created.data().path("slug").asText();
        assertThat(post("/api/v1/articles/" + id + "/publish", Map.of(), token)
                .status()).isEqualTo(200);

        // 热缓存：此时 likeCount=0
        HttpResult warm = get("/api/v1/articles/" + slug, null);
        assertThat(warm.data().path("likeCount").asInt()).isZero();

        // 点赞后再次读详情：命中缓存路径，但计数必须立即反映（回归：曾冻结最长 10 分钟）
        put("/api/v1/articles/" + id + "/reactions/like", Map.of(), token);
        HttpResult afterLike = get("/api/v1/articles/" + slug, null);
        assertThat(afterLike.data().path("likeCount").asInt())
                .as("缓存命中路径的 likeCount 必须来自新鲜行").isEqualTo(1);

        // 取消点赞同样立即回落
        delete("/api/v1/articles/" + id + "/reactions/like", token);
        HttpResult afterUnlike = get("/api/v1/articles/" + slug, null);
        assertThat(afterUnlike.data().path("likeCount").asInt()).isZero();
    }
}
