package com.yufeichi.server.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

// Not in default Surefire discovery. Two paid embedding requests, no Chat or real site data.
class LiveVectorSmoke {
    @org.junit.jupiter.api.BeforeAll static void suppressPrivateProviderDiagnostics() {
        for (String name : new String[]{"org.springframework.ai", "org.springframework.web.reactive.function.client", "com.zaxxer.hikari"})
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name)).setLevel(ch.qos.logback.classic.Level.OFF);
    }
    private String required(String name) {
        var value = System.getenv(name); assertThat(value != null && !value.isBlank()).as("Missing configuration: " + name).isTrue(); return value;
    }
    @Test void realEmbeddingWritesAndRetrievesFromIndependentPgvector() {
        assertThat(System.getenv("AI_SMOKE_ENABLED")).isEqualTo("true");
        var image = DockerImageName.parse("pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f").asCompatibleSubstituteFor("postgres");
        try (var pg = new PostgreSQLContainer<>(image).withDatabaseName("live_vector_smoke")
                .withUsername("smoke").withPassword(UUID.randomUUID().toString())) {
            pg.start();
            var properties = new AiProperties(); properties.setEnabled(true);
            var embedding = properties.getEmbedding(); embedding.setBaseUrl(required("AI_EMBEDDING_BASE_URL"));
            embedding.setApiKey(required("AI_EMBEDDING_API_KEY")); embedding.setModel(required("AI_EMBEDDING_MODEL"));
            embedding.setDimensions(Integer.parseInt(required("AI_EMBEDDING_DIMENSIONS")));
            var vector = properties.getVector(); vector.setEnabled(true); vector.setUrl(pg.getJdbcUrl());
            vector.setUsername(pg.getUsername()); vector.setPassword(pg.getPassword());
            var configuration = new AiVectorConfiguration(); var executor = configuration.aiEmbeddingExecutor(properties);
            try {
                var model = configuration.aiEmbeddingModel(properties, executor);
                try (var store = new AiVectorStore(properties, model)) {
                    String javaId = UUID.randomUUID().toString();
                    store.add(List.of(new Document(javaId, "公开验收夹具：Java21 Spring Boot后端接口连接MySQL，实现认证与数据库查询。", Map.of("fixture", true)),
                            new Document(UUID.randomUUID().toString(), "公开验收夹具：厨房烘焙蛋糕，准备面粉鸡蛋，控制烤箱温度。", Map.of("fixture", true))));
                    var hits = store.search(SearchRequest.builder().query("Java Spring Boot数据库后端开发")
                            .topK(1).similarityThreshold(0).filterExpression("fixture == true").build());
                    assertThat(model.dimensions()).isEqualTo(1024);
                    assertThat(hits).hasSize(1); assertThat(hits.getFirst().getId()).isEqualTo(javaId);
                    assertThat(store.healthy()).isTrue();
                }
            } catch (RuntimeException privateFailure) { throw new AssertionError("Live vector smoke failed; provider and database details suppressed"); }
            finally { executor.shutdownNow(); }
        }
    }
}
