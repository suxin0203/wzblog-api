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
}
