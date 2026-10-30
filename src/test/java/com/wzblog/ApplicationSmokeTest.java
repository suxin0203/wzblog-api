package com.wzblog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("wzblog")
            .withUsername("wzblog")
            .withPassword("wzblog");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 1);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesAllTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                """, Integer.class);
        assertThat(count).isEqualTo(32);
    }

    @Test
    void seedLoadsDefaultCategories() {
        Integer categories = jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class);
        assertThat(categories).isEqualTo(4);
    }

    @Test
    void pingReturnsUnifiedResponseAndTraceId() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/ping", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":\"OK\"");
        assertThat(response.getBody()).contains("\"status\":\"pong\"");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }

    @Test
    void unknownPathReturnsJson404() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/does-not-exist", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("\"code\":\"COMMON_NOT_FOUND\"");
    }
}