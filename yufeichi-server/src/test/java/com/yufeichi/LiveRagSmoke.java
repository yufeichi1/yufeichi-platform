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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: real index + two bounded questions, at most five paid requests, disposable databases only. */
@SpringBootTest(classes = YufeichiServerApplication.class, properties = {
        "app.ai.enabled=true", "app.ai.knowledge-enabled=true", "app.ai.vector.enabled=true"})
@ActiveProfiles("test") @Testcontainers
class LiveRagSmoke {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("live_rag_test").withUsername("smoke").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse(AiVectorIntegrationTests.IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("live_rag_vector").withUsername("smoke").withPassword(UUID.randomUUID().toString());
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        assertThat(System.getenv("AI_SMOKE_ENABLED")).isEqualTo("true");
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379)); r.add("spring.data.redis.password", () -> "");
        r.add("app.ai.vector.url", PG::getJdbcUrl); r.add("app.ai.vector.username", PG::getUsername); r.add("app.ai.vector.password", PG::getPassword);
        var mapping = Map.of("base-url", "AI_PROVIDER_BASE_URL", "api-key", "AI_API_KEY", "chat-model", "AI_CHAT_MODEL",
                "embedding.base-url", "AI_EMBEDDING_BASE_URL", "embedding.api-key", "AI_EMBEDDING_API_KEY", "embedding.model", "AI_EMBEDDING_MODEL", "embedding.dimensions", "AI_EMBEDDING_DIMENSIONS");
        mapping.forEach((name, variable) -> {
            assertThat(System.getenv(variable) != null && !System.getenv(variable).isBlank()).as("Missing " + variable).isTrue();
            r.add("app.ai." + name, () -> System.getenv(variable));
        });
        r.add("file.upload-path", () -> uploads.toString());
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AiKnowledgeIndex index;
    @Autowired AiKnowledgeService service;
    @Autowired AiProperties properties;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    KnowledgeResponse ask(String question) {
        var result=service.stream(service.prepare(new KnowledgeRequest(question),1)).filter(e->e.name().equals("done")).blockFirst(Duration.ofSeconds(65));
        assertThat(result).isNotNull(); return (KnowledgeResponse)result.data();
    }
    @Test void realKnownCitationAndUnknownQuestion() throws Exception {
        jdbc.update("INSERT INTO blog_article(id,title,content,author_id,status) VALUES(701,'公开本地RAG验收夹具','本演示项目的构建标识是CONSTELLATION-427。使用Java21和MySQL。这是测试文本，不是线上业务事实。',1,1),(702,'私密负例','PRIVATE_RAG_FIXTURE_MUST_NOT_LEAVE_MYSQL',1,0)");
        String id=index.submit(1);
        boolean indexed=false;
        for(int i=0;i<150;i++) {
            var job=jdbc.queryForMap("SELECT * FROM ai_index_job WHERE id=?",id);
            if(List.of("SUCCEEDED","FAILED").contains(job.get("status"))) { assertThat(job.get("status")).isEqualTo("SUCCEEDED");indexed=true;break; }
            Thread.sleep(1000);
        }
        assertThat(indexed).isTrue();
        String knownQuestion="本演示项目的构建标识是什么？";
        var known=ask(knownQuestion);
        assertThat(known.answered()).isTrue(); assertThat(known.answer()).contains("CONSTELLATION-427");
        assertThat(known.sources()).hasSize(1); assertThat(known.sources().getFirst().url()).isEqualTo("/articles/701");
        String unknownQuestion="本站推荐报考哪所大学研究生，录取概率是多少？";
        var unknown=ask(unknownQuestion); assertThat(unknown.answered()).isFalse(); assertThat(unknown.sources()).isEmpty();
        String calls=redis.opsForValue().get("ai:quota:global:day:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(Integer.parseInt(calls)).isBetween(4,5);
        var evidence=Map.of("chatModel",properties.getChatModel(),"embeddingModel",properties.getEmbedding().getModel(),
                "dimensions",1024,"reservedProviderCalls",calls,"knownQuestion",knownQuestion,"known",known,"unknownQuestion",unknownQuestion,"unknown",unknown);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/ai-rag-live-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("LIVE_RAG_PASS known_source=/articles/701 unknown_no_answer=true reservedProviderCalls="+calls);
    }
}
