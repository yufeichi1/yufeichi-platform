package com.yufeichi;

import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.ai.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = YufeichiServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test", "app.ai.enabled=true", "app.ai.vector.enabled=true",
                "app.ai.embedding.api-key=embedding-fixture-key", "app.ai.embedding.model=fixture-embedding"})
@ActiveProfiles("test") @Testcontainers
class AiVectorIntegrationTests {
    public static final String IMAGE = "pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_vector_test").withUsername("vector_test").withPassword("isolated-vector-test-password");
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("vector_business_test").withUsername("business_test").withPassword("isolated-business-test-password");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "isolated-redis-test-password");
    static final AiEmbeddingFixture MODEL = new AiEmbeddingFixture();
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword); registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379)); registry.add("spring.data.redis.password", () -> "isolated-redis-test-password");
        registry.add("app.ai.vector.url", PG::getJdbcUrl); registry.add("app.ai.vector.username", PG::getUsername);
        registry.add("app.ai.vector.password", PG::getPassword); registry.add("app.ai.embedding.base-url", MODEL::baseUrl);
        registry.add("file.upload-path", () -> uploads.toString());
    }
    @Autowired AiVectorStore store;
    @Autowired AiProperties properties;
    @Autowired AiEmbeddingModel embedding;
    @Autowired JdbcTemplate business;
    @Autowired DataSource businessSource;
    @Autowired TestRestTemplate http;
    @Autowired AiKnowledgeIndex index;
    @Autowired AiKnowledgeSources sources;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    JdbcTemplate vector;
    @BeforeEach void setup() {
        vector = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        store.initialize();
        // This table belongs solely to the fresh test container, never a development DB.
        vector.update("DELETE FROM yufeichi_ai.vector_store");
        MODEL.wrongDimensions = false; MODEL.calls.set(0);
        MODEL.failOnCall = 0; MODEL.afterInput = () -> { }; MODEL.inputs.clear();
        business.update("DELETE FROM ai_index_job");
        business.update("UPDATE ai_index_state SET active_version=NULL,manifest_hash=NULL,running_job=NULL WHERE id=1");
        business.update("DELETE FROM blog_article_tag"); business.update("DELETE FROM blog_article"); business.update("DELETE FROM project");
        var keys = redis.keys("ai:*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    @AfterAll static void close(@Autowired AiVectorStore store) { store.close(); MODEL.close(); }

    @Test void versionsSchemaAndBusinessDataSourceRemainIndependent() throws Exception {
        assertThat(business.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("vector_business_test");
        try (var connection = businessSource.getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql:"); }
        assertThat(business.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(11);
        assertThat(vector.queryForObject("SELECT current_database()", String.class)).isEqualTo("ai_vector_test");
        assertThat(vector.queryForObject("SELECT current_setting('server_version')", String.class)).startsWith("17.");
        assertThat(vector.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class)).isEqualTo("0.8.6");
        // Flyway also records schema creation; count actual versioned SQL migrations.
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM yufeichi_ai.vector_schema_history WHERE success=true AND version IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='yufeichi_ai' AND tablename='vector_store' AND indexdef LIKE '%USING hnsw%'", Integer.class)).isZero();
        assertThat(store.healthy()).isTrue();
        assertThat(MODEL.calls).hasValue(0);
    }
    @Test void writesExactSearchMetadataUpsertAndOwnedDeletionWork() {
        String javaId = UUID.randomUUID().toString(), vueId = UUID.randomUUID().toString();
        store.add(List.of(new Document(javaId, "Java后端验收夹具", Map.of("fixture", true, "kind", "backend")),
                new Document(vueId, "Vue前端验收夹具", Map.of("fixture", true, "kind", "frontend"))));
        var hits = store.search(SearchRequest.builder().query("Java框架").topK(1).similarityThreshold(0)
                .filterExpression("fixture == true").build());
        assertThat(hits).hasSize(1); assertThat(hits.getFirst().getId()).isEqualTo(javaId);
        assertThat(hits.getFirst().getMetadata()).containsEntry("kind", "backend");
        assertThat(MODEL.contractValid).isTrue();
        store.add(List.of(new Document(javaId, "Java修改后的验收夹具", Map.of("fixture", true, "kind", "backend"))));
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM yufeichi_ai.vector_store", Integer.class)).isEqualTo(2);
        assertThat(vector.queryForObject("SELECT content FROM yufeichi_ai.vector_store WHERE id=?::uuid", String.class, javaId)).contains("修改后");
        store.delete(List.of(javaId, vueId));
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM yufeichi_ai.vector_store", Integer.class)).isZero();
    }
    @Test void rejectsProviderDimensionMismatchBeforeInsert() {
        MODEL.wrongDimensions = true;
        assertThatThrownBy(() -> store.add(List.of(new Document("Java错误维度夹具"))))
                .isInstanceOf(AiProviderException.class).hasNoCause();
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM yufeichi_ai.vector_store", Integer.class)).isZero();
    }
    @Test void cannotSilentlyChangeModelOrDimensionsOfExistingSchema() {
        String originalModel = properties.getEmbedding().getModel();
        try {
            properties.getEmbedding().setDimensions(768);
            try (var incompatible = new AiVectorStore(properties, embedding)) {
                assertThatThrownBy(incompatible::initialize).isInstanceOf(AiProviderException.class).hasNoCause();
            }
            properties.getEmbedding().setDimensions(1024); properties.getEmbedding().setModel("different-model");
            try (var incompatible = new AiVectorStore(properties, embedding)) {
                assertThatThrownBy(incompatible::initialize).isInstanceOf(AiProviderException.class).hasNoCause();
            }
        } finally { properties.getEmbedding().setDimensions(1024); properties.getEmbedding().setModel(originalModel); }
        assertThat(vector.queryForObject("SELECT dimensions FROM yufeichi_ai.embedding_profile", Integer.class)).isEqualTo(1024);
        assertThat(store.healthy()).isTrue(); assertThat(MODEL.calls).hasValue(0);
    }
    @Test void vectorFailureDoesNotBreakBusinessDatabaseOrCoreHttp() {
        String originalUrl = properties.getVector().getUrl();
        try {
            properties.getVector().setUrl("jdbc:postgresql://127.0.0.1:9/unreachable_vector_test");
            try (var unavailable = new AiVectorStore(properties, embedding)) {
                assertThatThrownBy(unavailable::initialize).isInstanceOf(AiProviderException.class).hasNoCause();
            }
        } finally { properties.getVector().setUrl(originalUrl); }
        assertThat(business.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("vector_business_test");
        assertThat(http.getForEntity("/api/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/articles", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/projects", String.class).getStatusCode().value()).isEqualTo(200);
    }

    private void fixtures() {
        for (int i = 1; i <= 8; i++) business.update("INSERT INTO blog_article(id,title,content,author_id,status) VALUES(?,?,?,1,1)",
                i, "Java本地验收夹具" + i, "Java Spring Boot 工程验收内容。\n\n" + "独立测试数据库、权限校验与失败恢复。".repeat(80));
        business.update("INSERT INTO blog_article(id,title,content,author_id,status,deleted) VALUES(20,'私密草稿','DRAFT_SECRET',1,0,0),(21,'下架文章','HIDDEN_SECRET',1,2,0),(22,'已删除','DELETED_SECRET',1,1,1)");
        business.update("INSERT INTO project(id,name,description,tech_stack,status,deleted) VALUES(1,'本地项目夹具1','Java项目验收内容','Java,MySQL',1,0),(2,'本地项目夹具2','Vue项目验收内容','Vue',1,0),(3,'隐藏项目','PROJECT_SECRET',NULL,0,0),(4,'删除项目','PROJECT_DELETED',NULL,1,1)");
    }
    private Map<String, Object> awaitJob(String id) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var job = business.queryForMap("SELECT * FROM ai_index_job WHERE id=?", id);
            if (List.of("SUCCEEDED", "FAILED").contains(job.get("status"))) return job;
            Thread.sleep(100);
        }
        throw new AssertionError("Isolated index task did not finish");
    }
    private String activeVersion() { return business.queryForObject("SELECT active_version FROM ai_index_state WHERE id=1", String.class); }
    @Test void publishedOnlyParagraphsMetadataAndUnchangedRebuildAreIdempotent() throws Exception {
        fixtures();
        var job = awaitJob(index.submit(1));
        assertThat(job.get("status")).isEqualTo("SUCCEEDED"); assertThat(job.get("source_count")).isEqualTo(10);
        String version = activeVersion();
        var docs = sources.chunks(sources.read(false), version, "fixture-embedding", 1024);
        assertThat(store.matches(version, docs)).isTrue();
        assertThat(docs).allSatisfy(d -> {
            assertThat(d.getText()).hasSizeLessThanOrEqualTo(1024);
            assertThat(d.getMetadata()).containsKeys("sourceType", "sourceId", "contentHash", "indexVersion", "url");
        });
        assertThat(String.join("\n", MODEL.inputs)).doesNotContain("DRAFT_SECRET", "HIDDEN_SECRET", "DELETED_SECRET", "PROJECT_SECRET", "PROJECT_DELETED");
        int calls = MODEL.calls.get();
        assertThat(awaitJob(index.submit(1)).get("status")).isEqualTo("SUCCEEDED");
        assertThat(MODEL.calls).hasValue(calls); assertThat(activeVersion()).isEqualTo(version);
        assertThat(vector.queryForObject("SELECT COUNT(*) FROM yufeichi_ai.vector_store", Integer.class)).isEqualTo(docs.size());
    }
    @Test void partialFailureNeverSwitchesActiveVersionAndIsVisible() throws Exception {
        fixtures(); assertThat(awaitJob(index.submit(1)).get("status")).isEqualTo("SUCCEEDED");
        String original = activeVersion();
        var originalDocuments = sources.chunks(sources.read(false), original, "fixture-embedding", 1024);
        business.update("UPDATE blog_article SET content=CONCAT(content,'新版本') WHERE id=1");
        MODEL.failOnCall = MODEL.calls.get() + 2;
        var failed = awaitJob(index.submit(1));
        assertThat(failed.get("status")).isEqualTo("FAILED"); assertThat(failed.get("error_code")).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat((Integer) failed.get("completed_chunks")).isGreaterThan(0);
        assertThat(activeVersion()).isEqualTo(original);
        assertThat(index.status().get("jobs").toString()).contains("FAILED", "DEPENDENCY_UNAVAILABLE");
        assertThat(store.matches(original, originalDocuments)).isTrue();
    }
    @Test void changingVisibilityDuringEmbeddingAbortsActivation() throws Exception {
        fixtures();
        MODEL.afterInput = () -> { business.update("UPDATE blog_article SET status=2 WHERE id=1"); MODEL.afterInput = () -> { }; };
        var failed = awaitJob(index.submit(1));
        assertThat(failed.get("status")).isEqualTo("FAILED"); assertThat(failed.get("error_code")).isEqualTo("SOURCE_CHANGED");
        assertThat(activeVersion()).isNull();
    }
    @Test void queuedConflictRecoveryInputCapAndQuotaAreDurable() throws Exception {
        fixtures();
        business.update("INSERT INTO ai_index_job(id,requested_by,status) VALUES('interrupted-fixture',1,'RUNNING')");
        business.update("UPDATE ai_index_state SET running_job='interrupted-fixture' WHERE id=1");
        assertThatThrownBy(() -> index.submit(1)).isInstanceOf(com.yufeichi.server.common.error.BusinessException.class);
        assertThat(awaitJob("interrupted-fixture").get("error_code")).isEqualTo("INTERRUPTED");
        business.update("UPDATE blog_article SET content=? WHERE id=1", "超长夹具".repeat(30000));
        assertThat(awaitJob(index.submit(1)).get("error_code")).isEqualTo("INPUT_LIMIT"); assertThat(MODEL.calls).hasValue(0);
        business.update("UPDATE blog_article SET content='Java恢复后的夹具' WHERE id=1");
        redis.opsForValue().set("ai:quota:index:chars:" + java.time.LocalDate.now(java.time.ZoneOffset.UTC), "100000");
        assertThat(awaitJob(index.submit(1)).get("error_code")).isEqualTo("QUOTA"); assertThat(MODEL.calls).hasValue(0);
        assertThat(activeVersion()).isNull();
    }
    @Test void onlyRealSuperAdminCanSubmitAndReadJobs() throws Exception {
        business.update("UPDATE sys_user SET status=1 WHERE id=1");
        business.update("INSERT IGNORE INTO sys_user(id,username,password,status) SELECT 101,'index-reader',password,1 FROM sys_user WHERE id=1");
        var anonymous = http.getForEntity("/api/admin/ai/knowledge", String.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        var login = http.postForEntity("/api/auth/login", Map.of("username", "index-reader", "password", "Admin@123456"), com.fasterxml.jackson.databind.JsonNode.class);
        var headers = new org.springframework.http.HttpHeaders(); headers.setBearerAuth(login.getBody().path("data").path("token").asText());
        assertThat(http.exchange("/api/admin/ai/knowledge", org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(http.exchange("/api/admin/ai/knowledge/reindex", org.springframework.http.HttpMethod.POST, new org.springframework.http.HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(403);
        login = http.postForEntity("/api/auth/login", Map.of("username", "admin", "password", "Admin@123456"), com.fasterxml.jackson.databind.JsonNode.class);
        headers.setBearerAuth(login.getBody().path("data").path("token").asText());
        assertThat(http.exchange("/api/admin/ai/knowledge", org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);
        var created = http.exchange("/api/admin/ai/knowledge/reindex", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(awaitJob(created.getBody().path("data").path("jobId").asText()).get("status")).isEqualTo("SUCCEEDED");
        assertThat(MODEL.calls).hasValue(0); // Empty public corpus is a valid empty index, without paid probing.
    }
}
