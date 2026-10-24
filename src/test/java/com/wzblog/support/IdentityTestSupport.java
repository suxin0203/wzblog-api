package com.wzblog.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.identity.app.AdminUserAppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M1 集成测试基础设施：单例 MySQL/Redis 容器（JVM 内复用），HTTP 与 DB/Redis 断言工具。
 */
@org.springframework.test.context.TestPropertySource(properties = {
        "wzblog.admin.username=bootstrap_admin",
        "wzblog.admin.password=AdminPass123",
        "wzblog.admin.email=bootstrap@wzblog.test"
})
public abstract class IdentityTestSupport {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("wzblog")
            .withUsername("wzblog")
            .withPassword("wzblog");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 1);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AdminUserAppService adminUserAppService;

    protected record HttpResult(int status, JsonNode body, HttpHeaders headers) {

        public String code() {
            return body == null ? null : body.path("code").asText();
        }

        public JsonNode data() {
            return body == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : body.path("data");
        }

        public String error(String field) {
            return data().path("errors").path(field).asText();
        }
    }

    protected String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    protected HttpResult get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, token, null);
    }

    protected HttpResult post(String path, Object body, String token) {
        return exchange(HttpMethod.POST, path, body, token, null);
    }

    protected HttpResult post(String path, Object body, String token, String userAgent) {
        return exchange(HttpMethod.POST, path, body, token, userAgent);
    }

    protected HttpResult put(String path, Object body, String token) {
        return exchange(HttpMethod.PUT, path, body, token, null);
    }

    protected HttpResult delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, null, token, null);
    }

    protected HttpResult exchange(HttpMethod method, String path, Object body, String token, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (userAgent != null) {
            headers.set(HttpHeaders.USER_AGENT, userAgent);
        }
        ResponseEntity<String> response = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        JsonNode node = null;
        if (response.getBody() != null && !response.getBody().isBlank()) {
            try {
                node = objectMapper.readTree(response.getBody());
            } catch (Exception ignored) {
                node = null;
            }
        }
        return new HttpResult(response.getStatusCode().value(), node, response.getHeaders());
    }

    protected Map<String, Object> registerBody(String username, String password, String email, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("email", email);
        body.put("nickname", nickname);
        body.put("captchaId", "test-captcha");
        body.put("captchaCode", "abcd");
        return body;
    }

    protected HttpResult registerRaw(String username, String password, String email) {
        return post("/api/v1/auth/register", registerBody(username, password, email, null), null);
    }

    /** 注册并断言 201，返回用户 ID 字符串。 */
    protected String register(String username, String password, String email) {
        HttpResult result = registerRaw(username, password, email);
        org.assertj.core.api.Assertions.assertThat(result.status()).as("register status: " + result.body()).isEqualTo(201);
        return result.data().path("id").asText();
    }

    protected Map<String, Object> loginBody(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("captchaId", "test-captcha");
        body.put("captchaCode", "abcd");
        return body;
    }

    protected HttpResult loginRaw(String username, String password) {
        return loginRaw(username, password, null);
    }

    protected HttpResult loginRaw(String username, String password, String userAgent) {
        return post("/api/v1/auth/login", loginBody(username, password), null, userAgent);
    }

    protected String accessToken(String username, String password) {
        HttpResult result = loginRaw(username, password);
        org.assertj.core.api.Assertions.assertThat(result.status()).as("login status: " + result.body()).isEqualTo(200);
        return result.data().path("accessToken").asText();
    }

    protected String adminToken() {
        return accessToken("bootstrap_admin", "AdminPass123");
    }

    protected Long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    protected String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}