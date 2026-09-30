package com.yufeichi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.AiWireFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = YufeichiServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "app.ai.enabled=true", "app.ai.api-key=fixture-not-a-real-key",
                "app.ai.chat-model=fixture-model", "app.ai.request-timeout-seconds=3", "app.ai.connect-timeout-seconds=1",
                "app.ai.heartbeat-seconds=1"})
@ActiveProfiles("test")
@Testcontainers
class AiIntegrationTests {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ai_test").withUsername("ai_test").withPassword("isolated-ai-test-password");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "isolated-ai-redis-password");
    static final AiWireFixture MODEL = new AiWireFixture();
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "isolated-ai-redis-password");
        registry.add("app.ai.base-url", MODEL::baseUrl);
        registry.add("file.upload-path", () -> uploads.toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    private String admin;
    private String reader;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("ai_test");
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        jdbc.update("INSERT IGNORE INTO sys_user(id,username,password,status) SELECT 101,'ai-reader',password,1 FROM sys_user WHERE id=1");
        var keys = redis.keys("ai:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        keys = redis.keys("security:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        admin = login("admin");
        reader = login("ai-reader");
        MODEL.reset("normal");
    }
    @AfterAll static void close(@Autowired LettuceConnectionFactory redis) { redis.stop(); MODEL.close(); }
    private String login(String username) {
        var response = http.postForEntity("/api/auth/login", Map.of("username", username, "password", "Admin@123456"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data").path("token").asText();
    }
    private ResponseEntity<String> post(String token, String suffix, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (suffix.equals("/stream")) headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("/api/admin/ai/summary" + suffix, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
    @Test void authenticatedJsonUsesRealSpringAiAdapterAndNeverWritesArticle() {
        long articles = jdbc.queryForObject("SELECT COUNT(*) FROM blog_article", Long.class);
        var response = post(admin, "", Map.of("content", "测试正文"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("这是测试摘要。", "contentHash", "requestId");
        assertThat(MODEL.correctAuthorization).isTrue();
        assertThat(MODEL.structured).isTrue();
        assertThat(MODEL.calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_article", Long.class)).isEqualTo(articles);
    }
    @Test void permissionsAndInputValidationRejectBeforeModelCall() {
        assertThat(post(null, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(401);
        assertThat(post(reader, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(403);
        assertThat(post(null, "/stream", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(401);
        assertThat(post(reader, "/stream", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(403);
        assertThat(post(admin, "", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(post(admin, "", Map.of("content", " ")).getStatusCode().value()).isEqualTo(400);
        assertThat(post(admin, "", Map.of("content", "长".repeat(12001))).getStatusCode().value()).isEqualTo(400);
        assertThat(post(admin, "/stream", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(MODEL.calls).hasValue(0);
    }
    @Test void invalidAndOversizeModelOutputsAre502() {
        MODEL.reset("bad-json");
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(502);
        MODEL.reset("too-long");
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(502);
    }
    @Test void quotaIsAtomicAndRetryChargesGlobalAttempt() {
        MODEL.reset("transient");
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(200);
        assertThat(MODEL.calls).hasValue(2);
        assertThat(redis.opsForValue().get("ai:quota:global:day:" + java.time.LocalDate.now(java.time.ZoneOffset.UTC))).isEqualTo("2");
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(200);
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(429);
        var limitedStream = post(admin, "/stream", Map.of("content", "正文"));
        assertThat(limitedStream.getStatusCode().value()).isEqualTo(429);
        assertThat(limitedStream.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(MODEL.calls).hasValue(4);
    }
    @Test void providerAuthAnd429NeverRetryOrLeakPrivateBody() {
        for (String scenario : new String[]{"auth", "rate"}) {
            MODEL.reset(scenario);
            var response = post(admin, "", Map.of("content", "正文"));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).doesNotContain("PRIVATE", "fixture-not-a-real-key");
            assertThat(MODEL.calls).hasValue(1);
        }
    }
    @Test void timeoutCancelsAndReturns504() {
        MODEL.reset("timeout");
        var response = post(admin, "", Map.of("content", "正文"));
        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody()).contains("63002");
        MODEL.reset("normal");
        assertThat(post(admin, "", Map.of("content", "正文")).getStatusCode().value()).isEqualTo(200);
    }
    @Test void streamIsIncrementalOverHttpAndHasHeartbeatAndDone() throws Exception {
        MODEL.reset("slow");
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(http.getRootUri() + "/api/admin/ai/summary/stream"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"正文\"}")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        var received = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        long start = System.nanoTime();
        StringBuilder data = new StringBuilder();
        boolean firstDelta = false;
        for (String line; (line = received.readLine()) != null;) {
            data.append(line).append('\n');
            if (line.equals("event:delta") && !firstDelta) {
                firstDelta = true;
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            }
        }
        assertThat(firstDelta).isTrue();
        assertThat(data.toString()).contains("event:meta", "event:heartbeat", "event:done", "contentHash").doesNotContain("event:error");
        assertThat(MODEL.calls).hasValue(1);
    }
    @Test void truncatedProviderStreamEndsWithErrorInsteadOfDone() {
        MODEL.reset("unfinished");
        var response = post(admin, "/stream", Map.of("content", "正文"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("event:delta", "event:error", "63003").doesNotContain("event:done");
        assertThat(MODEL.calls).hasValue(1);
    }
}
