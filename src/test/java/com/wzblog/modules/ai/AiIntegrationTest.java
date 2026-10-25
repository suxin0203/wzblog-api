package com.wzblog.modules.ai;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 AI 集成测试（mock 上游）：SSE 事件顺序、游客令牌、配额、反馈、编辑辅助、后台统计。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiIntegrationTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    /** 流式读取一次对话，返回按行收集的 SSE 帧与解析出的事件名序列。 */
    private SseResult chat(String token, String guestToken, Map<String, Object> body) {
        AtomicReference<SseResult> holder = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            List<String> lines = new ArrayList<>();
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                if (token != null) {
                    headers.setBearerAuth(token);
                }
                if (guestToken != null) {
                    headers.set("X-AI-Session-Token", guestToken);
                }
                rest.execute("/api/v1/ai/chat/stream", HttpMethod.POST,
                        request -> {
                            request.getHeaders().putAll(headers);
                            request.getBody().write(
                                    objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
                        },
                        response -> {
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    lines.add(line);
                                    if (line.startsWith("event: done") || line.startsWith("event: error")) {
                                        // 读到 data 行后继续一轮再退出
                                        for (int i = 0; i < 2; i++) {
                                            String extra = reader.readLine();
                                            if (extra == null) {
                                                break;
                                            }
                                            lines.add(extra);
                                        }
                                        break;
                                    }
                                }
                            }
                            return null;
                        });
            } catch (Exception ignored) {
                // 流被客户端关闭属正常
            }
            List<String> events = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("event:")) {
                    events.add(line.substring(6).trim());
                }
            }
            holder.set(new SseResult(lines, events));
        });
        worker.start();
        try {
            worker.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder.get();
    }

    record SseResult(List<String> lines, List<String> events) {

        String dataOf(String event) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("event:") && line.substring(6).trim().equals(event)
                        && i + 1 < lines.size() && lines.get(i + 1).startsWith("data:")) {
                    return lines.get(i + 1).substring(5).trim();
                }
            }
            return null;
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------ 对话流

    @Test
    void guestChatEmitsSessionToolDeltaDone() {
        SseResult result = chat(null, null, Map.of("message", "帮我搜索一下虚拟线程相关的文章"));
        assertThat(result).isNotNull();
        assertThat(result.events()).isNotEmpty();

        // 首帧 session，游客发放令牌
        assertThat(result.events().get(0)).isEqualTo("session");
        JsonNode sessionData = parse(result.dataOf("session"));
        String sessionId = sessionData.path("sessionId").asText();
        String token = sessionData.path("sessionToken").asText();
        assertThat(sessionId).hasSize(26);
        assertThat(token).startsWith("wzai_");

        // 触发检索词 → 有 tool 事件（start+end）
        assertThat(result.events()).contains("tool", "delta", "done");
        assertThat(result.events().indexOf("session"))
                .isLessThan(result.events().indexOf("delta"));
        assertThat(result.events().indexOf("tool"))
                .isLessThan(result.events().indexOf("done"));

        JsonNode done = parse(result.dataOf("done"));
        assertThat(done.path("finishReason").asText()).isEqualTo("stop");
        String messageId = done.path("messageId").asText();
        assertThat(messageId).isNotBlank();

        // DB：USER/TOOL/ASSISTANT 各落一行，会话 message_count=3
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_messages WHERE session_id = ?", Long.class, sessionId);
        assertThat(rows).isEqualTo(3L);
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM ai_sessions WHERE id = ?", Integer.class, sessionId);
        assertThat(count).isEqualTo(3);
        String status = jdbc.queryForObject(
                "SELECT status FROM ai_messages WHERE id = ?", String.class, Long.valueOf(messageId));
        assertThat(status).isEqualTo("COMPLETE");

        // 游客令牌续聊：错误令牌 401（通过消息接口验证）
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-AI-Session-Token", "wzai_invalid");
        var response = rest.exchange("/api/v1/ai/sessions/" + sessionId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        // 正确令牌可读消息（默认无 TOOL 行）
        headers.set("X-AI-Session-Token", token);
        response = rest.exchange("/api/v1/ai/sessions/" + sessionId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        try {
            JsonNode list = objectMapper.readTree(response.getBody()).path("data");
            assertThat(list.size()).isEqualTo(2);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        // 反馈：游客对 ASSISTANT 消息反馈一次，重复 409
        HttpHeaders feedbackHeaders = new HttpHeaders();
        feedbackHeaders.setContentType(MediaType.APPLICATION_JSON);
        feedbackHeaders.set("X-AI-Session-Token", token);
        var feedback = rest.exchange("/api/v1/ai/messages/" + messageId + "/feedback",
                HttpMethod.POST, new HttpEntity<>("{\"rating\":1}", feedbackHeaders), String.class);
        assertThat(feedback.getStatusCode().value()).isEqualTo(200);
        var duplicate = rest.exchange("/api/v1/ai/messages/" + messageId + "/feedback",
                HttpMethod.POST, new HttpEntity<>("{\"rating\":-1}", feedbackHeaders), String.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void loggedInUserChatAndQuota() {
        String username = unique("ai");
        register(username, PASSWORD, null);
        String token = accessToken(username, PASSWORD);

        HttpResult quota = get("/api/v1/ai/quota", token);
        assertThat(quota.status()).isEqualTo(200);
        assertThat(quota.data().path("enabled").asBoolean()).isTrue();
        assertThat(quota.data().path("scope").asText()).isEqualTo("USER");

        SseResult result = chat(token, null, Map.of("message", "你好"));
        assertThat(result.events().get(0)).isEqualTo("session");
        JsonNode sessionData = parse(result.dataOf("session"));
        // 登录态不发放游客令牌
        assertThat(sessionData.path("sessionToken").asText()).isEmpty();
        String sessionId = sessionData.path("sessionId").asText();
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM ai_sessions WHERE id = ?", Long.class, sessionId);
        assertThat(userId).isEqualTo(userId(username));

        // 越权：他人访问 → 404
        String other = unique("ao");
        register(other, PASSWORD, null);
        assertThat(get("/api/v1/ai/sessions/" + sessionId + "/messages",
                accessToken(other, PASSWORD)).status()).isEqualTo(404);
    }

    @Test
    void messageTooLongRejected() {
        HttpResult result = post("/api/v1/ai/chat/stream",
                Map.of("message", "长".repeat(501)), null);
        // 未经 SSE 提交前返回 JSON 400
        assertThat(result.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------ 编辑辅助与后台

    @Test
    void summaryAndTagsSuggestion() {
        String token = adminToken();
        Map<String, Object> article = new java.util.LinkedHashMap<>();
        article.put("title", unique("虚拟线程入门"));
        article.put("contentMd", "# 虚拟线程\n\nJava 21 的虚拟线程适合大量阻塞 IO。");
        HttpResult created = post("/api/v1/articles", article, token);
        assertThat(created.status()).isEqualTo(201);
        long articleId = created.data().path("id").asLong();

        HttpResult summary = post("/api/v1/articles/" + articleId + "/ai/summary",
                Map.of("style", "CONCISE", "length", 120), token);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.data().path("summary").asText()).isNotBlank();
        assertThat(summary.data().path("model").asText()).contains("mock");

        HttpResult tags = post("/api/v1/articles/" + articleId + "/ai/tags",
                Map.of("count", 3), token);
        assertThat(tags.status()).isEqualTo(200);
        assertThat(tags.data().path("tags").isArray()).isTrue();

        // 普通用户无权限
        String username = unique("ae");
        register(username, PASSWORD, null);
        assertThat(post("/api/v1/articles/" + articleId + "/ai/summary", Map.of(),
                accessToken(username, PASSWORD)).status()).isEqualTo(403);
    }

    @Test
    void adminSessionsAndStats() {
        chat(null, null, Map.of("message", "统计测试用的一次提问"));

        HttpResult sessions = get("/api/v1/admin/ai/sessions", adminToken());
        assertThat(sessions.status()).isEqualTo(200);
        assertThat(sessions.data().path("total").asLong()).isGreaterThanOrEqualTo(1);

        HttpResult stats = get("/api/v1/admin/ai/stats", adminToken());
        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.data().path("messages").asLong()).isGreaterThanOrEqualTo(2);

        // 普通用户 403
        String username = unique("as");
        register(username, PASSWORD, null);
        assertThat(get("/api/v1/admin/ai/sessions", accessToken(username, PASSWORD))
                .status()).isEqualTo(403);
    }
}
