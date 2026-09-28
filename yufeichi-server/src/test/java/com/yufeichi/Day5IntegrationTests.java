package com.yufeichi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.config.BootstrapConfiguration;
import com.yufeichi.server.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=YufeichiServerApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.profiles.active=test")
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Day5IntegrationTests {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("day5_test").withUsername("day5_test").withPassword(UUID.randomUUID().toString());
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PROD_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", REDIS_PASSWORD);
    @TempDir static Path uploads;
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        r.add("file.upload-path", () -> uploads.toString());
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisSecurityStore store;
    @Autowired JwtTokenProvider tokens;
    @Autowired TestRestTemplate http;

    @BeforeEach void cleanOwnSecurityKeys() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("day5_test");
        Set<String> keys = redis.keys("security:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @AfterAll static void closeClientsAndOwnLogFiles(@Autowired LettuceConnectionFactory factory) {
        factory.stop();
        // Nested production contexts share Logback's JVM singleton. Release only this test's
        // file appenders before JUnit removes its temporary directory (Windows locks open files).
        var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        for (String name : List.of("APP_FILE", "ERROR_FILE")) {
            var appender=root.getAppender(name);
            if (appender instanceof ch.qos.logback.core.FileAppender<?> file
                    && Path.of(file.getFile()).toAbsolutePath().normalize().startsWith(uploads.toAbsolutePath().normalize())) {
                root.detachAppender(appender); appender.stop();
            }
        }
    }

    String[] prodArgs(boolean bootstrap) {
        var args = new ArrayList<>(List.of("--spring.profiles.active=" + (bootstrap ? "prod,bootstrap" : "prod"),
                "--DB_HOST=" + MYSQL.getHost(), "--DB_PORT=" + MYSQL.getMappedPort(3306), "--DB_NAME=day5_test",
                "--DB_USERNAME=" + MYSQL.getUsername(), "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--REDIS_HOST=" + REDIS.getHost(), "--REDIS_PORT=" + REDIS.getMappedPort(6379),
                "--REDIS_PASSWORD=" + REDIS_PASSWORD, "--JWT_SECRET=" + PROD_SECRET,
                "--UPLOAD_PATH=" + uploads, "--logging.file.path=" + uploads.resolve("logs"), "--server.port=0", "--BOOTSTRAP_USERNAME=day5-owner",
                "--BOOTSTRAP_PASSWORD=" + PASSWORD));
        return args.toArray(String[]::new);
    }

    @Test @Order(1) void seedDisabledOfflineBootstrapIsOneTimeAndProductionDocsAreClosed() {
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class)).isZero();
        assertThat(loginResponse("admin", "Admin@123456").getStatusCode().value()).isEqualTo(401);
        assertThatThrownBy(() -> new SpringApplicationBuilder(YufeichiServerApplication.class).run(prodArgs(false)))
                .hasRootCauseMessage("Run the offline --bootstrap command before starting production");
        YufeichiServerApplication.main(java.util.stream.Stream.concat(Arrays.stream(prodArgs(true)),
                java.util.stream.Stream.of("--bootstrap")).toArray(String[]::new));
        assertThat(jdbc.queryForObject("SELECT completed FROM sys_bootstrap_state WHERE id=1", Boolean.class)).isTrue();
        String encoded = jdbc.queryForObject("SELECT password FROM sys_user WHERE username='day5-owner'", String.class);
        assertThat(encoded).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThatThrownBy(() -> new SpringApplicationBuilder(BootstrapConfiguration.class)
                .web(WebApplicationType.NONE).run(prodArgs(true)))
                .hasMessage("Bootstrap has already completed");
        try (var prod = new SpringApplicationBuilder(YufeichiServerApplication.class).run(prodArgs(false))) {
            int port = ((ServletWebServerApplicationContext) prod).getWebServer().getPort();
            var client = new TestRestTemplate();
            var login = client.postForEntity("http://127.0.0.1:" + port + "/api/auth/login",
                    Map.of("username", "day5-owner", "password", PASSWORD), JsonNode.class);
            assertThat(login.getStatusCode().value()).isEqualTo(200);
            var headers = new HttpHeaders(); headers.setBearerAuth(login.getBody().path("data").path("token").asText());
            for (String path : List.of("/v3/api-docs", "/doc.html", "/swagger-ui/index.html")) {
                assertThat(client.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                        new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(403);
            }
            assertThat(prod.getEnvironment().getProperty("mybatis-plus.configuration.log-impl")).endsWith("NoLoggingImpl");
        }
    }

    @Test @Order(2) void logoutRevokesOnlyThisSessionUntilItsOriginalExpiry() {
        String first = login(), second = login();
        assertThat(first).isNotEqualTo(second);
        var me = call(HttpMethod.GET, "/api/auth/me", first);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().path("data").path("permissions").toString()).contains("article:publish");
        assertThat(call(HttpMethod.POST, "/api/auth/logout", first).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/auth/me", first).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/auth/me", second).getStatusCode().value()).isEqualTo(200);
        String key = RedisSecurityStore.revokedKey(first);
        assertThat(key).doesNotContain(first).matches("security:revoked:[a-f0-9]{64}");
        long remaining = tokens.getExpiresAt(first) - System.currentTimeMillis();
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(remaining - 1000, remaining + 1000);
        assertThat(remaining).isBetween(44L * 60 * 1000, 45L * 60 * 1000);
        store.revoke(first, tokens.getExpiresAt(first));
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
    }

    @Test @Order(3) void normalizedAccountFailuresCannotBeBypassedAndReturn429() {
        for (String name : List.of("day5-owner", "DAY5-OWNER", " day5-owner ", "ｄａｙ５-owner", "dáy5-owner"))
            assertThat(loginResponse(name, "wrong-password").getStatusCode().value()).isEqualTo(401);
        assertThat(loginResponse("day5-owner", PASSWORD).getStatusCode().value()).isEqualTo(429);
        for (String key : store.failureKeys("day5-owner", "127.0.0.1")) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("5");
            assertThat(redis.getExpire(key)).isBetween(590L, 600L);
        }
    }

    @Test @Order(4) void failureCountersAreAtomicExpireAndSuccessfulLoginDoesNotResetSharedIp() throws Exception {
        var shortWindow = new RedisSecurityStore(redis, 1000, 1000, 2);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 40; i++) tasks.add(() -> { shortWindow.failedLogin("parallel", "192.0.2.1"); return null; });
            for (Future<Void> result : executor.invokeAll(tasks)) result.get();
        }
        var keys = shortWindow.failureKeys("parallel", "192.0.2.1");
        for (String key : keys) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("40");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        }
        shortWindow.successfulLogin("parallel");
        assertThat(redis.hasKey(keys.get(0))).isFalse();
        assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("40");
        Thread.sleep(2100);
        assertThat(redis.hasKey(keys.get(1))).isFalse();
        shortWindow.checkLogin("parallel", "192.0.2.1");
    }

    @Test @Order(5) void realRedisOutageReturns503ForAuthenticationButPublicReadStillWorks() {
        String token = login();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (String path : List.of("/api/articles", "/api/projects", "/api/categories", "/api/tags"))
                assertThat(call(HttpMethod.GET, path, "invalid-optional-token").getStatusCode().value()).isEqualTo(200);
            assertThat(call(HttpMethod.GET, "/api/auth/me", token).getStatusCode().value()).isEqualTo(503);
            assertThat(call(HttpMethod.POST, "/api/auth/logout", token).getStatusCode().value()).isEqualTo(503);
            assertThat(loginResponse("day5-owner", PASSWORD).getStatusCode().value()).isEqualTo(503);
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        assertThat(call(HttpMethod.GET, "/api/auth/me", token).getStatusCode().value()).isEqualTo(200);
    }

    ResponseEntity<JsonNode> loginResponse(String name, String password) {
        return http.postForEntity("/api/auth/login", Map.of("username", name, "password", password), JsonNode.class);
    }
    String login() {
        var response = loginResponse("day5-owner", PASSWORD);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("data").path("token").asText();
    }
    ResponseEntity<JsonNode> call(HttpMethod method, String path, String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(headers), JsonNode.class);
    }
}
