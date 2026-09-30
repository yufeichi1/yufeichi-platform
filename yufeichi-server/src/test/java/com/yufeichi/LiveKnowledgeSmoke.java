package com.yufeichi;

import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only: one embedding batch over two labelled disposable public fixtures, no Chat. */
@SpringBootTest(classes = YufeichiServerApplication.class, properties = {
        "spring.profiles.active=test", "app.ai.enabled=true", "app.ai.vector.enabled=true"})
@ActiveProfiles("test") @Testcontainers
class LiveKnowledgeSmoke {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("live_index_test").withUsername("smoke").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse(AiVectorIntegrationTests.IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("live_index_vector").withUsername("smoke").withPassword(UUID.randomUUID().toString());
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        assertThat(System.getenv("AI_SMOKE_ENABLED")).isEqualTo("true");
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379)); r.add("spring.data.redis.password", () -> "");
        r.add("app.ai.vector.url", PG::getJdbcUrl); r.add("app.ai.vector.username", PG::getUsername); r.add("app.ai.vector.password", PG::getPassword);
        for (String name : List.of("base-url", "api-key", "model", "dimensions")) {
            String variable = "AI_EMBEDDING_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
            assertThat(System.getenv(variable) != null && !System.getenv(variable).isBlank()).as("Missing " + variable).isTrue();
            r.add("app.ai.embedding." + name, () -> System.getenv(variable));
        }
        r.add("file.upload-path", () -> uploads.toString());
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AiKnowledgeIndex index;
    @Autowired AiKnowledgeSources sources;
    @Autowired AiVectorStore vector;
    @Autowired AiProperties properties;
    @Test void realPublishedFixturesActivateAndRepeatWithoutNewRows() throws Exception {
        jdbc.update("INSERT INTO blog_article(title,content,author_id,status) VALUES('公开本地验收夹具','Java21和Spring Boot构建后台，MySQL保存业务数据。',1,1),('私密负例','PRIVATE_FIXTURE_MUST_NOT_LEAVE_MYSQL',1,0)");
        jdbc.update("INSERT INTO project(name,description,tech_stack,status) VALUES('公开项目验收夹具','项目采用独立测试数据库和Docker部署。','Java,Vue,MySQL',1)");
        var first = await(index.submit(1)); assertThat(first.get("source_count")).isEqualTo(2);
        String version = first.get("index_version").toString();
        assertThat(vector.matches(version, sources.chunks(sources.read(false), version, properties.getEmbedding().getModel(), 1024))).isTrue();
        assertThat(await(index.submit(1)).get("index_version")).isEqualTo(version);
    }
    private Map<String, Object> await(String id) throws Exception {
        for (int i = 0; i < 150; i++) {
            var job = jdbc.queryForMap("SELECT * FROM ai_index_job WHERE id=?", id);
            if (List.of("SUCCEEDED", "FAILED").contains(job.get("status"))) {
                assertThat(job.get("status")).isEqualTo("SUCCEEDED"); return job;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Live isolated index timed out");
    }
}
