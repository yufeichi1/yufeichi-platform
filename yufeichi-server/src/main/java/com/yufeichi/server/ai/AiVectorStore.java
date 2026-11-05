package com.yufeichi.server.ai;

import com.zaxxer.hikari.HikariDataSource;
import com.yufeichi.server.common.error.ErrorCode;
import org.flywaydb.core.Flyway;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Internal vector component; the staged index pipeline is the only business writer. */
public final class AiVectorStore implements AutoCloseable {
    public static final String SCHEMA = "yufeichi_ai";
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final PgVectorStore store;
    private final int dimensions;
    private final String model;
    private volatile boolean ready;
    private String cleanupCursor = "00000000-0000-0000-0000-000000000000";

    public AiVectorStore(AiProperties properties, EmbeddingModel embedding) {
        properties.getVector().validate(); properties.getEmbedding().validate();
        dimensions = properties.getEmbedding().getDimensions();
        model = properties.getEmbedding().getModel();
        // An empty HikariDataSource starts only on first use, never during core startup.
        pool = new HikariDataSource();
        pool.setJdbcUrl(properties.getVector().getUrl());
        pool.setUsername(properties.getVector().getUsername()); pool.setPassword(properties.getVector().getPassword());
        pool.setPoolName("ai-vector"); pool.setMaximumPoolSize(2); pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5000); pool.setValidationTimeout(2000); pool.setInitializationFailTimeout(-1);
        pool.addDataSourceProperty("connectTimeout", "5"); pool.addDataSourceProperty("socketTimeout", "30");
        pool.addDataSourceProperty("options", "-c statement_timeout=5000");
        jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
        store = PgVectorStore.builder(jdbc, embedding).schemaName(SCHEMA).vectorTableName("vector_store")
                .dimensions(dimensions).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE).initializeSchema(false)
                // The caller already bounds 20 x 1024 characters, below this provider's batch token limit.
                // One reserved quota slot must correspond to exactly one embedding HTTP call.
                .batchingStrategy(documents -> List.of(documents))
                .removeExistingVectorStoreTable(false).maxDocumentBatchSize(20).build();
    }
    public void initialize() { safely(() -> { ensureReady(); return null; }); }
    private synchronized void ensureReady() {
        if (ready) return;
        Flyway.configure().dataSource(pool).locations("classpath:db/vector/migration")
                .schemas(SCHEMA).defaultSchema(SCHEMA).table("vector_schema_history")
                .placeholders(Map.of("embeddingDimensions", Integer.toString(dimensions)))
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load().migrate();
        jdbc.update("INSERT INTO yufeichi_ai.embedding_profile(singleton,model,dimensions) VALUES(true,?,?) ON CONFLICT DO NOTHING", model, dimensions);
        var profile = jdbc.queryForMap("SELECT model,dimensions FROM yufeichi_ai.embedding_profile WHERE singleton=true");
        Integer actual = jdbc.queryForObject("SELECT a.atttypmod FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='yufeichi_ai' AND c.relname='vector_store' AND a.attname='embedding'", Integer.class);
        if (!model.equals(profile.get("model")) || !Integer.valueOf(dimensions).equals(profile.get("dimensions"))
                || !Integer.valueOf(dimensions).equals(actual)) throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false);
        ready = true;
    }
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty() || documents.size() > 20) throw new IllegalArgumentException("Invalid bounded vector batch");
        safely(() -> { ensureReady(); store.add(documents); return null; });
    }
    public List<Document> search(SearchRequest request) {
        if (request == null || request.getTopK() < 1 || request.getTopK() > 10) throw new IllegalArgumentException("Invalid vector search limit");
        return safely(() -> { ensureReady(); return store.similaritySearch(request); });
    }
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20) throw new IllegalArgumentException("Invalid bounded vector delete");
        safely(() -> { ensureReady(); store.delete(ids); return null; });
    }
    public boolean healthy() { return safely(() -> { ensureReady(); return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); }); }
    public boolean matches(String version, List<Document> expected) {
        java.util.UUID.fromString(version);
        return safely(() -> {
            ensureReady();
            var rows = jdbc.queryForList("SELECT id::text,content,metadata FROM yufeichi_ai.vector_store WHERE metadata->>'indexVersion'=? LIMIT 101", version);
            if (rows.size() != expected.size()) return false;
            var content = new java.util.HashMap<String, String>();
            var metadata = new java.util.HashMap<String, Map<String, Object>>();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            for (var row : rows) {
                String id = row.get("id").toString(); content.put(id, row.get("content").toString());
                try { metadata.put(id, json.readValue(row.get("metadata").toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { })); }
                catch (java.io.IOException invalid) { return false; }
            }
            return expected.stream().allMatch(d -> java.util.Objects.equals(content.get(d.getId()), d.getText())
                    && d.getMetadata().entrySet().stream().allMatch(e -> metadata.containsKey(d.getId())
                    && String.valueOf(e.getValue()).equals(String.valueOf(metadata.get(d.getId()).get(e.getKey())))));
        });
    }
    /** Delete bounded, verifiably system-owned obsolete rows only. Foreign rows remain untouched. */
    public synchronized int pruneOwnedObsolete(String activeVersion, AiKnowledgeSources.Snapshot snapshot) {
        return safely(() -> {
            ensureReady();
            var live = new java.util.HashMap<String, String>();
            snapshot.sources().forEach(s -> live.put(s.type() + ":" + s.id(), s.hash()));
            var rows = jdbc.queryForList("SELECT id::text,metadata FROM yufeichi_ai.vector_store WHERE metadata->>'chunker'='paragraph-v1' AND id>?::uuid ORDER BY id LIMIT 1000", cleanupCursor);
            // Rotate bounded pages so foreign or malformed rows cannot permanently starve later owned rows.
            cleanupCursor = rows.size() < 1000 ? "00000000-0000-0000-0000-000000000000" : rows.getLast().get("id").toString();
            var obsolete = new java.util.ArrayList<String>();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            for (var row : rows) {
                try {
                    var m = json.readTree(row.get("metadata").toString());
                    String version = m.path("indexVersion").asText(), type = m.path("sourceType").asText(), hash = m.path("contentHash").asText();
                    java.util.UUID.fromString(version);
                    long sourceId = Long.parseLong(m.path("sourceId").asText());
                    int chunk = Integer.parseInt(m.path("chunk").asText());
                    if (!List.of("article", "project").contains(type) || sourceId < 1 || chunk < 0 || chunk >= 100
                            || !hash.matches("[a-f0-9]{64}") || !model.equals(m.path("embeddingModel").asText())
                            || dimensions != m.path("dimensions").asInt()) continue;
                    String key = version + ":" + type + ":" + sourceId + ":" + hash + ":" + chunk;
                    String ownedId = java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    if (!ownedId.equals(row.get("id").toString())) continue;
                    if (!version.equals(activeVersion) || !hash.equals(live.get(type + ":" + sourceId))) obsolete.add(ownedId);
                } catch (java.io.IOException | IllegalArgumentException malformed) { /* Never delete unidentifiable data. */ }
            }
            for (int offset = 0; offset < obsolete.size(); offset += 20)
                store.delete(obsolete.subList(offset, Math.min(offset + 20, obsolete.size())));
            return obsolete.size();
        });
    }
    private <T> T safely(Supplier<T> operation) {
        try { return operation.get(); }
        catch (AiProviderException safe) { throw safe; }
        catch (RuntimeException privateFailure) { throw new AiProviderException(ErrorCode.AI_UNAVAILABLE, false); }
    }
    @Override public void close() { pool.close(); }
}
