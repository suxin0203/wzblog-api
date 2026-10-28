package com.wzblog.modules.interaction;

import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论限流：独立 context，收紧阈值验证 429 与 Retry-After。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wzblog.interaction.user-comment-short-window=10",
        "wzblog.interaction.user-comment-short-limit=1",
        "wzblog.interaction.user-comment-daily-limit=50"
})
class CommentRateLimitTest extends IdentityTestSupport {

    private static final String PASSWORD = "Passw0rd1";

    @Test
    void secondCommentWithinWindowIsLimited() {
        String token = adminToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", unique("rl"));
        body.put("contentMd", "# t\n\nbody");
        HttpResult created = post("/api/v1/articles", body, token);
        assertThat(created.status()).isEqualTo(200);
        long article = created.data().path("id").asLong();
        assertThat(post("/api/v1/articles/" + article + "/publish", Map.of(), token)
                .status()).isEqualTo(200);

        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("content", "第一条");
        assertThat(post("/api/v1/articles/" + article + "/comments", comment, token)
                .status()).isEqualTo(200);

        comment.put("content", "第二条");
        HttpResult second = post("/api/v1/articles/" + article + "/comments", comment, token);
        assertThat(second.status()).isEqualTo(429);
        assertThat(second.code()).isEqualTo("COMMON_RATE_LIMITED");
        assertThat(second.headers().getFirst("Retry-After")).isNotNull();
    }
}
