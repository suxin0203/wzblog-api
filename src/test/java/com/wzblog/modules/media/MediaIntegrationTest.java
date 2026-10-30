package com.wzblog.modules.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 媒体集成测试：预签名/直传/确认/去重/引用计数/删除/URL/清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MediaIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^(articles|avatars|banners|goods)/\\d{4}/\\d{2}/[0-9a-f]{32}\\.(jpg|png|webp)$");

    @Autowired
    private TestRestTemplate rest;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int serverPort;

    // ------------------------------------------------------------ 工具

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private Parsed presign(String token, String filename, String contentType, long size,
                               String bizType) {
        Map<String, Object> body = Map.of(
                "filename", filename, "contentType", contentType,
                "size", size, "bizType", bizType);
        return toParsed(post("/api/v1/files/presign", body, token));
    }

    private Parsed upload(String token, String filename, String contentType, byte[] bytes,
                              String bizType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, fileHeaders));
        body.add("bizType", bizType);
        ResponseEntity<String> response = rest.postForEntity("/api/v1/files/upload",
                new HttpEntity<>(body, headers), String.class);
        return parse(response);
    }

    private Parsed toParsed(com.wzblog.support.IdentityTestSupport.HttpResult result) {
        return new Parsed(result.status(), result.body());
    }

    private record Parsed(int status, com.fasterxml.jackson.databind.JsonNode body) {

        String code() {
            return body == null ? null : body.path("code").asText();
        }

        JsonNode data() {
            return body == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                    : body.path("data");
        }
    }

    private Parsed parse(ResponseEntity<String> response) {
        JsonNode node = null;
        try {
            if (response.getBody() != null) {
                node = objectMapper.readTree(response.getBody());
            }
        } catch (Exception ignored) {
        }
        return new Parsed(response.getStatusCode().value(), node);
    }

    // ------------------------------------------------------------ 预签名

    @Test
    void presignCreatesTempRowAndCapabilityUrl() throws Exception {
        String token = adminToken();
        byte[] png = pngBytes(10, 10);
        Parsed result = presign(token, "cover.png", "image/png", png.length, "ARTICLE_COVER");
        assertThat(result.status()).isEqualTo(200);
        String objectKey = result.data().path("objectKey").asText();
        assertThat(objectKey).matches(KEY_PATTERN);
        assertThat(result.data().path("uploadUrl").asText()).contains("/media-upload/");
        assertThat(result.data().path("fileId").asText()).isNotBlank();

        long fileId = result.data().path("fileId").asLong();
        String status = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(status).isEqualTo("TEMP");
    }

    @Test
    void presignRoleMatrix() throws Exception {
        String username = unique("m5user");
        register(username, PASSWORD, null);
        String userToken = accessToken(username, PASSWORD);

        byte[] png = pngBytes(8, 8);
        Parsed asUser = presign(userToken, "a.png", "image/png", png.length, "ARTICLE_COVER");
        assertThat(asUser.status()).isEqualTo(403);
        assertThat(asUser.code()).isEqualTo("FILE_PRESIGN_FORBIDDEN");

        Parsed editorAvatar = presign(adminToken(), "a.png", "image/png", png.length, "AVATAR");
        assertThat(editorAvatar.status()).isEqualTo(403);

        Parsed oversize = presign(adminToken(), "big.png", "image/png", 6 * 1024 * 1024,
                "ARTICLE_COVER");
        assertThat(oversize.status()).isEqualTo(400);
        assertThat(oversize.code()).isEqualTo("FILE_SIZE_EXCEEDED");
    }

    // ------------------------------------------------------------ 直传 + 确认

    @Test
    void directUploadThenConfirmActivatesFile() throws Exception {
        String token = adminToken();
        byte[] png = pngBytes(64, 48);
        Parsed presigned = presign(token, "cover.png", "image/png", png.length, "ARTICLE_COVER");
        assertThat(presigned.status()).isEqualTo(200);
        String uploadUrl = relativePath(presigned.data().path("uploadUrl").asText());
        long fileId = presigned.data().path("fileId").asLong();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> put = rest.exchange(uploadUrl, HttpMethod.PUT,
                new HttpEntity<>(png, headers), String.class);
        assertThat(put.getStatusCode().value()).isEqualTo(200);

        Parsed confirm = toParsed(post("/api/v1/files/" + fileId + "/confirm", Map.of(), token));
        assertThat(confirm.status()).isEqualTo(200);
        assertThat(confirm.data().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(confirm.data().path("mimeType").asText()).isEqualTo("image/png");
        assertThat(confirm.data().path("width").asInt()).isEqualTo(64);
        assertThat(confirm.data().path("height").asInt()).isEqualTo(48);
        // sha256 属后台字段，FileVO 不返回
        assertThat(confirm.data().has("sha256")).isFalse();
        assertThat(confirm.data().path("publicUrl").asText()).contains("/media/");

        // 二次确认幂等
        Parsed again = toParsed(post("/api/v1/files/" + fileId + "/confirm", Map.of(), token));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.data().path("status").asText()).isEqualTo("ACTIVE");

        // 公开 URL 可匿名读取
        String publicUrl = relativePath(confirm.data().path("publicUrl").asText());
        ResponseEntity<byte[]> served = rest.getForEntity(publicUrl, byte[].class);
        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getHeaders().getContentType().toString()).contains("image/png");
        assertThat(served.getBody()).isEqualTo(png);
    }

    @Test
    void confirmWithoutObjectReturns422() throws Exception {
        String token = adminToken();
        byte[] png = pngBytes(8, 8);
        Parsed presigned = presign(token, "x.png", "image/png", png.length, "CONTENT_IMAGE");
        long fileId = presigned.data().path("fileId").asLong();

        Parsed confirm = toParsed(post("/api/v1/files/" + fileId + "/confirm", Map.of(), token));
        assertThat(confirm.status()).isEqualTo(422);
        assertThat(confirm.code()).isEqualTo("FILE_OBJECT_MISSING");

        String status = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(status).isEqualTo("TEMP");
    }

    @Test
    void typeMasqueradeIsRejectedAndCleaned() throws Exception {
        String token = adminToken();
        byte[] fake = "this is not an image, just plain text pretending to be png".getBytes();
        Parsed presigned = presign(token, "evil.png", "image/png", fake.length, "CONTENT_IMAGE");
        long fileId = presigned.data().path("fileId").asLong();
        String uploadUrl = relativePath(presigned.data().path("uploadUrl").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(rest.exchange(uploadUrl, HttpMethod.PUT, new HttpEntity<>(fake, headers), String.class)
                .getStatusCode().value()).isEqualTo(200);

        Parsed confirm = toParsed(post("/api/v1/files/" + fileId + "/confirm", Map.of(), token));
        assertThat(confirm.status()).isEqualTo(400);
        assertThat(confirm.code()).isEqualTo("FILE_TYPE_UNSUPPORTED");

        String status = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(status).isEqualTo("DELETED");
    }

    @Test
    void pixelBombIsRejectedWithoutFullDecode() throws Exception {
        String token = adminToken();
        byte[] bomb = pngBytes(9000, 12); // 宽度超 8000 上限
        Parsed presigned = presign(token, "bomb.png", "image/png", bomb.length, "CONTENT_IMAGE");
        long fileId = presigned.data().path("fileId").asLong();
        String uploadUrl = relativePath(presigned.data().path("uploadUrl").asText());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        rest.exchange(uploadUrl, HttpMethod.PUT, new HttpEntity<>(bomb, headers), String.class);

        Parsed confirm = toParsed(post("/api/v1/files/" + fileId + "/confirm", Map.of(), token));
        assertThat(confirm.status()).isEqualTo(400);
        assertThat(confirm.code()).isEqualTo("FILE_PIXEL_EXCEEDED");
    }

    // ------------------------------------------------------------ multipart 兜底

    @Test
    void multipartUploadDeduplicatesSameContent() throws Exception {
        String username = unique("m5up");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        byte[] png = pngBytes(20, 20);

        Parsed first = upload(token, "avatar.png", "image/png", png, "AVATAR");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data().path("status").asText()).isEqualTo("ACTIVE");

        Parsed second = upload(token, "avatar2.png", "image/png", png, "AVATAR");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.data().path("fileId").asText()).isEqualTo(first.data().path("fileId").asText());
        assertThat(second.data().path("objectKey").asText())
                .isEqualTo(first.data().path("objectKey").asText());

        Long realCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM files WHERE sha256 = ?", Long.class, sha256Hex(png));
        assertThat(realCount).isEqualTo(1L);
    }

    @Test
    void multipartRejectsGifAndWrongMime() throws Exception {
        String username = unique("m5gif");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);

        byte[] gif = "GIF89a.....".getBytes();
        Parsed gifResult = upload(token, "x.gif", "image/gif", gif, "AVATAR");
        assertThat(gifResult.status()).isEqualTo(400);

        byte[] png = pngBytes(5, 5);
        Parsed mismatch = upload(token, "x.png", "image/jpeg", png, "AVATAR");
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.code()).isEqualTo("FILE_TYPE_UNSUPPORTED");
    }

    // ------------------------------------------------------------ 引用与删除

    @Test
    void avatarUpdateClaimsAndReleasesReference() throws Exception {
        String username = unique("m5ava");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        byte[] png = pngBytes(16, 16);

        Parsed uploaded = upload(token, "me.png", "image/png", png, "AVATAR");
        assertThat(uploaded.status()).isEqualTo(200);
        String objectKey = uploaded.data().path("objectKey").asText();
        long fileId = uploaded.data().path("fileId").asLong();

        Parsed profile = toParsed(put("/api/v1/users/me",
                Map.of("nickname", "nick", "avatarKey", objectKey), token));
        assertThat(profile.status()).isEqualTo(200);
        assertThat(refCount(fileId)).isEqualTo(1);

        // 换头像：新 +1，旧 -1
        byte[] other = pngBytes(18, 18);
        Parsed second = upload(token, "me2.png", "image/png", other, "AVATAR");
        String secondKey = second.data().path("objectKey").asText();
        Parsed switched = toParsed(put("/api/v1/users/me",
                Map.of("nickname", "nick", "avatarKey", secondKey), token));
        assertThat(switched.status()).isEqualTo(200);
        assertThat(refCount(fileId)).isZero();

        // 引用他人文件被拒
        String otherUser = unique("m5th");
        register(otherUser, PASSWORD, null);
        String otherToken = accessToken(otherUser, PASSWORD);
        Parsed steal = toParsed(put("/api/v1/users/me",
                Map.of("nickname", "n", "avatarKey", secondKey), otherToken));
        assertThat(steal.status()).isEqualTo(403);
    }

    @Test
    void deleteHonorsReferenceAndOwnership() throws Exception {
        String username = unique("m5del");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        byte[] png = pngBytes(12, 12);
        Parsed uploaded = upload(token, "f.png", "image/png", png, "AVATAR");
        long fileId = uploaded.data().path("fileId").asLong();

        // 引用中 → 409
        put("/api/v1/users/me", Map.of("nickname", "n", "avatarKey",
                uploaded.data().path("objectKey").asText()), token);
        Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
        assertThat(inUse.status()).isEqualTo(409);
        assertThat(inUse.code()).isEqualTo("FILE_IN_USE");

        // 释放后可删；他人删除被拒
        put("/api/v1/users/me", Map.of("nickname", "n2", "avatarKey", ""), token);
        String otherUser = unique("m5oth");
        register(otherUser, PASSWORD, null);
        Parsed forbidden = toParsed(delete("/api/v1/files/" + fileId, accessToken(otherUser, PASSWORD)));
        assertThat(forbidden.status()).isEqualTo(403);

        Parsed deleted = toParsed(delete("/api/v1/files/" + fileId, token));
        assertThat(deleted.status()).isEqualTo(200);
        Parsed again = toParsed(delete("/api/v1/files/" + fileId, token));
        assertThat(again.status()).isEqualTo(404);

        String status = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(status).isEqualTo("DELETED");
    }

    // ------------------------------------------------------------ T-013 实时引用删除保护

    private Long createProbeArticle() {
        String slug = unique("t13a");
        jdbc.update("""
                INSERT INTO articles (author_id, title, slug, summary, content_md, content_html,
                    content_text, status, visibility, is_pinned, allow_comment, word_count,
                    reading_seconds, view_count, view_external_count, view_internal_count,
                    like_count, favorite_count, comment_count, version, created_at, updated_at)
                VALUES (1, 'T013 引用测试', ?, '', '', '', '', 'PUBLISHED', 'PUBLIC', 0, 1, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, slug);
        return jdbc.queryForObject("SELECT id FROM articles WHERE slug = ?", Long.class, slug);
    }

    @Test
    void deleteRejectsArticleCoverEvenWhenRefCountZero() throws Exception {
        String token = adminToken();
        byte[] png = pngBytes(15, 15);
        Parsed uploaded = upload(token, "cover.png", "image/png", png, "ARTICLE_COVER");
        long fileId = uploaded.data().path("fileId").asLong();
        String objectKey = uploaded.data().path("objectKey").asText();
        assertThat(refCount(fileId)).isZero();

        Parsed duplicate = upload(token, "cover-again.png", "image/png", png, "ARTICLE_COVER");
        assertThat(duplicate.data().path("fileId").asText()).isEqualTo(String.valueOf(fileId));

        Long articleId = createProbeArticle();
        try {
            jdbc.update("UPDATE articles SET cover_key = ? WHERE id = ?", objectKey, articleId);
            Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.code()).isEqualTo("FILE_IN_USE");
            assertThat(inUse.data().path("references")).anySatisfy(node -> {
                assertThat(node.path("table").asText()).isEqualTo("articles");
                assertThat(node.path("column").asText()).isEqualTo("cover_key");
                assertThat(node.path("id").asText()).isEqualTo(String.valueOf(articleId));
            });
        } finally {
            jdbc.update("DELETE FROM articles WHERE id = ?", articleId);
        }

        assertThat(toParsed(delete("/api/v1/files/" + fileId, token)).status()).isEqualTo(200);
    }

    @Test
    void deleteRejectsMarkdownContentReference() throws Exception {
        String token = adminToken();
        Parsed uploaded = upload(token, "md.png", "image/png", pngBytes(16, 16), "CONTENT_IMAGE");
        long fileId = uploaded.data().path("fileId").asLong();
        String objectKey = uploaded.data().path("objectKey").asText();

        Long articleId = createProbeArticle();
        try {
            jdbc.update("UPDATE articles SET content_md = ? WHERE id = ?",
                    "![](/media/" + objectKey + ")", articleId);
            Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.data().path("references")).anySatisfy(node -> {
                assertThat(node.path("table").asText()).isEqualTo("articles");
                assertThat(node.path("column").asText()).isEqualTo("content_md");
                assertThat(node.path("id").asText()).isEqualTo(String.valueOf(articleId));
            });
        } finally {
            jdbc.update("DELETE FROM articles WHERE id = ?", articleId);
        }
        assertThat(toParsed(delete("/api/v1/files/" + fileId, token)).status()).isEqualTo(200);
    }

    @Test
    void deleteRejectsRenderedHtmlReference() throws Exception {
        String token = adminToken();
        Parsed uploaded = upload(token, "html.png", "image/png", pngBytes(17, 17), "CONTENT_IMAGE");
        long fileId = uploaded.data().path("fileId").asLong();
        String objectKey = uploaded.data().path("objectKey").asText();

        Long articleId = createProbeArticle();
        try {
            jdbc.update("UPDATE articles SET content_html = ? WHERE id = ?",
                    "<p><img src=\"/media/" + objectKey + "\" /></p>", articleId);
            Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.data().path("references")).anySatisfy(node ->
                    assertThat(node.path("column").asText()).isEqualTo("content_html"));
        } finally {
            jdbc.update("DELETE FROM articles WHERE id = ?", articleId);
        }
        assertThat(toParsed(delete("/api/v1/files/" + fileId, token)).status()).isEqualTo(200);
    }

    @Test
    void deleteRejectsHistoricalRevisionReference() throws Exception {
        String token = adminToken();
        Parsed uploaded = upload(token, "rev.png", "image/png", pngBytes(18, 18), "CONTENT_IMAGE");
        long fileId = uploaded.data().path("fileId").asLong();
        String objectKey = uploaded.data().path("objectKey").asText();

        Long articleId = createProbeArticle();
        try {
            jdbc.update("""
                    INSERT INTO article_revisions (article_id, revision_no, editor_id, title,
                        summary, content_md, change_note)
                    VALUES (?, 99901, 1, 'T013 历史引用', '', ?, 'T013')
                    """, articleId, "![](/media/" + objectKey + ")");
            Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.data().path("references")).anySatisfy(node -> {
                assertThat(node.path("table").asText()).isEqualTo("article_revisions");
                assertThat(node.path("column").asText()).isEqualTo("content_md");
                assertThat(node.path("historical").asBoolean()).isTrue();
            });
        } finally {
            jdbc.update("DELETE FROM article_revisions WHERE article_id = ?", articleId);
            jdbc.update("DELETE FROM articles WHERE id = ?", articleId);
        }
        assertThat(toParsed(delete("/api/v1/files/" + fileId, token)).status()).isEqualTo(200);
    }

    @Test
    void deleteRejectsFriendLinkReferenceWithoutRefCount() throws Exception {
        String token = adminToken();
        Parsed uploaded = upload(token, "logo.png", "image/png", pngBytes(19, 19), "CONTENT_IMAGE");
        long fileId = uploaded.data().path("fileId").asLong();
        String objectKey = uploaded.data().path("objectKey").asText();
        assertThat(refCount(fileId)).isZero();

        String blogName = unique("t13blog");
        jdbc.update("""
                INSERT INTO friend_links (blog_name, blog_url, logo_key, sort_order)
                VALUES (?, 'https://example.com', ?, 999)
                """, blogName, objectKey);
        Long linkId = jdbc.queryForObject(
                "SELECT id FROM friend_links WHERE blog_name = ?", Long.class, blogName);
        try {
            Parsed inUse = toParsed(delete("/api/v1/files/" + fileId, token));
            assertThat(inUse.status()).isEqualTo(409);
            assertThat(inUse.data().path("references")).anySatisfy(node -> {
                assertThat(node.path("table").asText()).isEqualTo("friend_links");
                assertThat(node.path("column").asText()).isEqualTo("logo_key");
                assertThat(node.path("id").asText()).isEqualTo(String.valueOf(linkId));
            });
        } finally {
            jdbc.update("DELETE FROM friend_links WHERE id = ?", linkId);
        }
        assertThat(toParsed(delete("/api/v1/files/" + fileId, token)).status()).isEqualTo(200);
    }

    @Test
    void tempUrlRequiresSignatureAndOwnership() throws Exception {
        String username = unique("m5url");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);
        byte[] png = pngBytes(9, 9);
        Parsed presigned = presign(adminToken(), "t.png", "image/png", png.length, "ARTICLE_COVER");
        long fileId = presigned.data().path("fileId").asLong();

        // 匿名对 TEMP 取 URL → 401（需登录）
        Parsed anonymous = toParsed(get("/api/v1/files/" + fileId + "/url", null));
        assertThat(anonymous.status()).isEqualTo(401);

        // 上传者之外的登录用户 → 403
        Parsed other = toParsed(get("/api/v1/files/" + fileId + "/url", token));
        assertThat(other.status()).isEqualTo(403);

        // 上传者（admin）拿到签名 URL，可直接读取；去掉签名则 404
        Parsed owner = toParsed(get("/api/v1/files/" + fileId + "/url", adminToken()));
        assertThat(owner.status()).isEqualTo(200);
        String signedUrl = owner.data().path("url").asText();
        assertThat(signedUrl).contains("exp=").contains("sig=");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        rest.exchange(relativePath(presigned.data().path("uploadUrl").asText()), HttpMethod.PUT,
                new HttpEntity<>(png, headers), String.class);
        ResponseEntity<byte[]> served = rest.getForEntity(relativePath(signedUrl), byte[].class);
        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getBody()).isEqualTo(png);

        String publicUrl = relativePath(signedUrl.substring(0, signedUrl.indexOf("?exp=")));
        assertThat(rest.getForEntity(publicUrl, byte[].class).getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------ 清理

    @Test
    void cleanupDryRunAndExpiredTemp() throws Exception {
        byte[] png = pngBytes(7, 7);
        Parsed presigned = presign(adminToken(), "old.png", "image/png", png.length, "CONTENT_IMAGE");
        long fileId = presigned.data().path("fileId").asLong();
        jdbc.update("UPDATE files SET created_at = DATE_SUB(NOW(3), INTERVAL 25 HOUR) WHERE id = ?",
                fileId);

        Parsed dry = toParsed(post("/api/v1/admin/files/cleanup", Map.of("dryRun", true), adminToken()));
        assertThat(dry.status()).isEqualTo(200);
        assertThat(dry.data().path("dryRun").asBoolean()).isTrue();
        String statusAfterDry = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(statusAfterDry).isEqualTo("TEMP");

        Parsed real = toParsed(post("/api/v1/admin/files/cleanup", Map.of("dryRun", false), adminToken()));
        assertThat(real.status()).isEqualTo(200);
        assertThat(real.data().path("dryRun").asBoolean()).isFalse();
        String statusAfterRun = jdbc.queryForObject("SELECT status FROM files WHERE id = ?",
                String.class, fileId);
        assertThat(statusAfterRun).isEqualTo("DELETED");
    }

    @Test
    void adminListFiltersByStatus() throws Exception {
        Parsed list = toParsed(get("/api/v1/admin/files?status=TEMP&page=1&pageSize=10", adminToken()));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().path("list").isArray()).isTrue();
        assertThat(list.data().path("total").asLong()).isGreaterThanOrEqualTo(0);

        String username = unique("m5adm");
        register(username, PASSWORD, null);
        Parsed forbidden = toParsed(get("/api/v1/admin/files", accessToken(username, PASSWORD)));
        assertThat(forbidden.status()).isEqualTo(403);
    }

    private int refCount(long fileId) {
        Integer count = jdbc.queryForObject("SELECT ref_count FROM files WHERE id = ?",
                Integer.class, fileId);
        return count == null ? -1 : count;
    }

    /** presign 返回的绝对 URL 指向固定端口；测试为随机端口，转换为相对路径再由 TestRestTemplate 解析。 */
    private String relativePath(String url) {
        int index = url.indexOf("/media");
        return index < 0 ? url : url.substring(index);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void identicalBytesAcrossUsersBothSucceed() throws Exception {
        // 回归：全局 sha256 去重曾把用户 B 的同字节上传误判为"复用他人文件"(403)
        byte[] png = pngBytes(24, 24);
        String first = unique("du1");
        register(first, PASSWORD, null);
        Parsed uploadA = upload(accessToken(first, PASSWORD), "me.png",
                "image/png", png, "AVATAR");
        assertThat(uploadA.status()).isEqualTo(200);

        String second = unique("du2");
        register(second, PASSWORD, null);
        Parsed uploadB = upload(accessToken(second, PASSWORD), "me.png",
                "image/png", png, "AVATAR");
        assertThat(uploadB.status()).as("body=%s", uploadB.body()).isEqualTo(200);
        assertThat(uploadB.data().path("objectKey").asText())
                .isNotEqualTo(uploadA.data().path("objectKey").asText());
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM files WHERE sha256 = ?", Long.class, sha256Hex(png));
        assertThat(rows).isEqualTo(2L); // 各自成行，不做跨用户别名
    }

    @Test
    void oversizeMultipartReturnsBusinessErrorNot500() {
        // 回归：multipart 上限未配置时 1~5MB 即 500；这里打 >5MB 验证业务码。
        // 手工构造 multipart 并带 Content-Length（分块传输会被服务端掐断，拿不到响应）
        byte[] tooBig = new byte[6 * 1024 * 1024];
        new java.util.Random().nextBytes(tooBig);
        String boundary = "----wztest" + unique("b");
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"bizType\"\r\n\r\n"
                + "CONTENT_IMAGE\r\n--" + boundary + "--\r\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(tooBig);
            out.write(tail.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        // JDK HttpClient：byte[] body 自动携带 Content-Length（TestRestTemplate 走分块会被掐断）
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + serverPort + "/api/v1/files/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + adminToken())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        java.net.http.HttpResponse<String> response;
        try {
            response = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        JsonNode node = null;
        try {
            node = objectMapper.readTree(response.body());
        } catch (Exception ignored) {
        }
        Parsed result = new Parsed(response.statusCode(), node);
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.code()).isEqualTo("FILE_SIZE_EXCEEDED");
    }
}
