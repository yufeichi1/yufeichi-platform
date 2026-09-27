package com.yufeichi;

import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = YufeichiServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test")
@ActiveProfiles("test")
@Testcontainers
class YufeichiServerApplicationTests {

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploadRoot;

    @AfterAll
    static void closeClientsBeforeContainers(@Autowired LettuceConnectionFactory connectionFactory) {
        connectionFactory.stop();
    }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("yufeichi_test")
            .withUsername("day1_test")
            .withPassword("isolated-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "isolated-redis-password");

    @DynamicPropertySource
    static void isolatedServices(DynamicPropertyRegistry registry) {
        registry.add("file.upload-path", () -> uploadRoot.toString());
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "isolated-redis-password");
    }

    @Autowired
    Environment environment;

    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper userMapper;
    @Autowired Flyway flyway;
    @Autowired StringRedisTemplate redis;
    @Autowired TestRestTemplate http;
    @Autowired PasswordEncoder passwords;

    @Test
    void openApiAndKnife4jAreAvailableOverHttp() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("openapi").asText()).startsWith("3.");
        assertThat(response.getBody().path("paths").has("/api/auth/login")).isTrue();
        assertThat(response.getBody().path("components").path("securitySchemes").has("BearerAuth")).isTrue();
        var ui = http.getForEntity("/doc.html", String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).containsIgnoringCase("<html");
        var config = http.getForEntity("/v3/api-docs/swagger-config", JsonNode.class);
        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getBody().path("url").asText()).isEqualTo("/v3/api-docs");
    }

    @Test
    void correctPasswordAndNormalTokenReachRealMeEndpoint() {
        var login = login("admin", "Admin@123456");
        assertResult(login, 200, 0);
        assertThat(login.getBody().path("data").path("permissions").isArray()).isTrue();
        var me = getWithToken("/api/auth/me", login.getBody().path("data").path("token").asText());
        assertResult(me, 200, 0);
        assertThat(me.getBody().path("data").path("username").asText()).isEqualTo("admin");
        assertThat(me.getBody().path("data").path("roles").toString()).contains("super_admin");
        assertThat(me.getBody().path("data").path("permissions").toString()).contains("article:add", "article:publish");
        assertThat(me.getBody().path("data").has("password")).isFalse();
    }

    @Test
    void wrongPasswordReturns401() {
        assertResult(login("admin", "incorrect-test-password"), 401, 50001);
    }

    @Test
    void emptyJsonReturns400() {
        assertResult(http.postForEntity("/api/auth/login", Map.of(), JsonNode.class), 400, 40000);
    }

    @Test
    void missingTokenReturns401() {
        assertResult(http.getForEntity("/api/auth/me", JsonNode.class), 401, 40100);
    }

    @ParameterizedTest(name = "HTTP rejects {0} token")
    @ValueSource(strings = {"expired", "tampered", "invalid-subject", "overflow-subject", "missing-expiry", "unknown-user"})
    void invalidTokensReturn401OverHttp(String kind) {
        var key = Keys.hmacShaKeyFor(environment.getRequiredProperty("jwt.secret").getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder().subject(switch (kind) {
            case "invalid-subject" -> "not-a-user-id";
            case "overflow-subject" -> "999999999999999999999999";
            case "unknown-user" -> "99999999";
            default -> "1";
        });
        if (!kind.equals("missing-expiry")) {
            builder.expiration(Date.from(Instant.now().plusSeconds(kind.equals("expired") ? -60 : 600)));
        }
        String token = builder.signWith(key).compact();
        if (kind.equals("tampered")) {
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    payload.replace("\"sub\":\"1\"", "\"sub\":\"2\"").getBytes(StandardCharsets.UTF_8));
            token = String.join(".", parts);
        }
        assertResult(getWithToken("/api/auth/me", token), 401, 40100);
        // A supplied invalid credential is rejected consistently even on a public endpoint.
        assertResult(getWithToken("/api/health", token), 401, 40100);
    }

    @Test
    void disablingUserImmediatelyRejectsPreviouslyIssuedToken() {
        String username = "day1-disabled-token";
        String token = createTestUserAndLogin(username);
        assertResult(getWithToken("/api/auth/me", token), 200, 0);
        jdbc.update("UPDATE sys_user SET status=0 WHERE username=?", username);
        assertResult(getWithToken("/api/auth/me", token), 401, 40100);
        assertResult(login(username, "day1-fixture-password"), 401, 50002);
    }

    @Test
    void deletingUserImmediatelyRejectsPreviouslyIssuedToken() {
        String username = "day1-deleted-token";
        String token = createTestUserAndLogin(username);
        assertResult(getWithToken("/api/auth/me", token), 200, 0);
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE username=?", username);
        assertResult(getWithToken("/api/auth/me", token), 401, 40100);
    }

    private String createTestUserAndLogin(String username) {
        // Autocommit is intentional: the real HTTP request uses a separate DB connection.
        // These fixtures exist only in the disposable yufeichi_test container.
        jdbc.update("INSERT INTO sys_user(username,password,status) VALUES(?,?,1)",
                username, passwords.encode("day1-fixture-password"));
        var response = login(username, "day1-fixture-password");
        assertResult(response, 200, 0);
        String token = response.getBody().path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private ResponseEntity<JsonNode> login(String username, String password) {
        return http.postForEntity("/api/auth/login", Map.of("username", username, "password", password), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getWithToken(String path, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private void assertResult(ResponseEntity<JsonNode> response, int status, int code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asInt()).isEqualTo(code);
    }

    @Test
    void contextLoads() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(MYSQL.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("yufeichi_test");
    }

    @Test
    void realMysql84Redis7AndAllMigrationChecksumsAreValid() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).hasSize(9);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND checksum IS NOT NULL", Integer.class))
                .isEqualTo(9);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
            assertThat(connection.serverCommands().info("server").getProperty("redis_version")).startsWith("7.");
        }
    }

    @Test
    @Transactional
    void roleAndPermissionQueriesDeduplicateSortAndFilterInactiveRows() {
        assertThat(jdbc.queryForObject("SELECT @@session.sql_mode", String.class)).contains("ONLY_FULL_GROUP_BY");
        jdbc.update("INSERT INTO sys_user(id,username,password,status) VALUES(1000,'audit-user','test-only',1)");
        jdbc.update("""
                INSERT INTO sys_role(id,role_code,role_name,status,deleted) VALUES
                (1000,'audit_reader','reader',1,0),(1001,'audit_writer','writer',1,0),
                (1002,'audit_disabled','disabled',0,0),(1003,'audit_deleted','deleted',1,1)
                """);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(1000,1000),(1000,1001),(1000,1002),(1000,1003)");
        jdbc.update("""
                INSERT INTO sys_role_permission(role_id,permission_id) VALUES
                (1000,1),(1000,2),(1001,1),(1001,2),(1000,3),(1000,4),(1002,5),(1003,6)
                """);
        jdbc.update("UPDATE sys_permission SET status=0 WHERE id=3");
        jdbc.update("UPDATE sys_permission SET deleted=1 WHERE id=4");
        assertThat(userMapper.selectRoleCodesByUserId(1000L)).containsExactly("audit_reader", "audit_writer");
        assertThat(userMapper.selectPermissionCodesByUserId(1000L)).containsExactly("article:add", "article:list");
    }

    @Test
    void oldDistinctSortIsRejectedByMysqlDefaultMode() {
        assertThatThrownBy(() -> jdbc.queryForList("SELECT DISTINCT role_code FROM sys_role ORDER BY sort_order,id"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORDER BY");
        assertThatThrownBy(() -> jdbc.queryForList("SELECT DISTINCT permission_code FROM sys_permission ORDER BY sort_order,id"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORDER BY");
    }

}
