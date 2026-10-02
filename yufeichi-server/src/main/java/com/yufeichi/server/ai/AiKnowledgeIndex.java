package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Durable single-job queue. Provider I/O is outside short business transactions. */
@Service
public class AiKnowledgeIndex {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AiKnowledgeSources sources;
    private final ObjectProvider<AiVectorStore> vectors;
    private final AiProperties properties;
    private final AiRequestGuard guard;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "ai-index-worker"); t.setDaemon(true); return t;
    });
    public AiKnowledgeIndex(JdbcTemplate jdbc, PlatformTransactionManager transactions, AiKnowledgeSources sources,
                            ObjectProvider<AiVectorStore> vectors, AiProperties properties, AiRequestGuard guard) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.sources = sources;
        this.vectors = vectors; this.properties = properties; this.guard = guard;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (available()) worker.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS); }
    private boolean available() { return properties.isEnabled() && properties.getVector().isEnabled() && vectors.getIfAvailable() != null; }
    public Map<String, Object> status() {
        var state = jdbc.queryForMap("SELECT active_version,activated_at,running_job FROM ai_index_state WHERE id=1");
        var result = new LinkedHashMap<String, Object>(); result.put("available", available()); result.put("state", state);
        result.put("jobs", jdbc.queryForList("SELECT id,status,index_version,source_count,chunk_count,completed_chunks,error_code,created_at,finished_at FROM ai_index_job ORDER BY created_at DESC,id DESC LIMIT 20"));
        return result;
    }
    public String submit(long userId) {
        if (!available()) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        return tx.execute(status -> {
            var state = jdbc.queryForMap("SELECT running_job FROM ai_index_state WHERE id=1 FOR UPDATE");
            if (state.get("running_job") != null) throw new BusinessException(ErrorCode.CONFLICT);
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ai_index_job(id,requested_by,status) VALUES(?,?,'QUEUED')", id, userId);
            jdbc.update("UPDATE ai_index_state SET running_job=? WHERE id=1", id);
            return id;
        });
    }
    private void poll() {
        // MySQL connection-scoped mutex also prevents two app instances from running paid jobs.
        // A crashed connection releases it; the next worker records interruption instead of auto-retrying paid calls.
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            try (var lock = connection.prepareStatement("SELECT GET_LOCK('yufeichi-ai-index',0)")) {
                lock.setQueryTimeout(5);
                try (var row = lock.executeQuery()) { if (!row.next() || row.getInt(1) != 1) return; }
            }
            try {
                String id = jdbc.queryForObject("SELECT running_job FROM ai_index_state WHERE id=1", String.class);
                if (id == null) return;
                String status = jdbc.queryForObject("SELECT status FROM ai_index_job WHERE id=?", String.class, id);
                if (!"QUEUED".equals(status)) { fail(id, "INTERRUPTED"); return; }
                jdbc.update("UPDATE ai_index_job SET status='RUNNING',started_at=NOW() WHERE id=? AND status='QUEUED'", id);
                run(id);
            } finally {
                try (var unlock = connection.prepareStatement("SELECT RELEASE_LOCK('yufeichi-ai-index')")) { unlock.execute(); }
            }
        } catch (RuntimeException | java.sql.SQLException failure) {
            // No SQL/provider messages, content, credentials or endpoint URLs enter logs.
            LoggerFactory.getLogger(getClass()).warn("AI index worker unavailable");
        }
    }
    private void run(String id) {
        try {
            Instant deadline = Instant.now().plusSeconds(properties.getIndexTimeoutSeconds());
            var vector = Objects.requireNonNull(vectors.getIfAvailable());
            vector.initialize();
            var snapshot = sources.read(false);
            checkDeadline(deadline);
            var state = jdbc.queryForMap("SELECT active_version,manifest_hash FROM ai_index_state WHERE id=1");
            String version = UUID.randomUUID().toString();
            if (snapshot.hash().equals(state.get("manifest_hash")) && state.get("active_version") != null)
                version = state.get("active_version").toString();
            var documents = sources.chunks(snapshot, version, properties.getEmbedding().getModel(), properties.getEmbedding().getDimensions());
            jdbc.update("UPDATE ai_index_job SET index_version=?,manifest_hash=?,source_count=?,chunk_count=? WHERE id=?", version, snapshot.hash(), snapshot.sources().size(), documents.size(), id);
            // An unchanged, verified version consumes no embedding request and creates no duplicates.
            if (!vector.matches(version, documents)) {
                if (version.equals(state.get("active_version"))) version = UUID.randomUUID().toString();
                documents = sources.chunks(snapshot, version, properties.getEmbedding().getModel(), properties.getEmbedding().getDimensions());
                jdbc.update("UPDATE ai_index_job SET index_version=? WHERE id=?", version, id);
                for (int offset = 0; offset < documents.size(); offset += 20) {
                    checkDeadline(deadline);
                    var batch = documents.subList(offset, Math.min(offset + 20, documents.size()));
                    int chars = batch.stream().mapToInt(d -> Objects.requireNonNull(d.getText()).length()).sum();
                    try (var lease = guard.acquireIndexBatch(chars)) { vector.add(batch); }
                    checkDeadline(deadline);
                    jdbc.update("UPDATE ai_index_job SET completed_chunks=? WHERE id=?", offset + batch.size(), id);
                }
                if (!vector.matches(version, documents)) throw new IndexFailure("VECTOR_VALIDATION");
            }
            checkDeadline(deadline);
            final String completeVersion = version;
            final int count = documents.size();
            tx.executeWithoutResult(transaction -> {
                // Short shared row/range locks fence concurrent publication/edit/deletion at activation.
                if (!sources.read(true).hash().equals(snapshot.hash())) throw new IndexFailure("SOURCE_CHANGED");
                checkDeadline(deadline);
                int changed = jdbc.update("UPDATE ai_index_state SET active_version=?,manifest_hash=?,activated_at=NOW(),running_job=NULL WHERE id=1 AND running_job=?", completeVersion, snapshot.hash(), id);
                if (changed != 1) throw new IndexFailure("INTERRUPTED");
                jdbc.update("UPDATE ai_index_job SET status='SUCCEEDED',completed_chunks=?,finished_at=NOW() WHERE id=? AND status='RUNNING'", count, id);
            });
            LoggerFactory.getLogger(getClass()).info("AI index job={} status=SUCCEEDED chunks={}", id, count);
        } catch (RuntimeException error) {
            String code = error instanceof IndexFailure f ? f.code : error instanceof AiKnowledgeSources.IndexLimitException ? "INPUT_LIMIT"
                    : error instanceof BusinessException b && b.getCode() == ErrorCode.TOO_MANY_REQUESTS.getCode() ? "QUOTA"
                    : "DEPENDENCY_UNAVAILABLE";
            fail(id, code);
        }
    }
    private void fail(String id, String code) {
        tx.executeWithoutResult(transaction -> {
            jdbc.update("UPDATE ai_index_job SET status='FAILED',error_code=?,finished_at=NOW() WHERE id=? AND status IN ('RUNNING','QUEUED')", code, id);
            jdbc.update("UPDATE ai_index_state SET running_job=NULL WHERE id=1 AND running_job=?", id);
        });
        LoggerFactory.getLogger(getClass()).warn("AI index job={} status=FAILED code={}", id, code);
    }
    private static void checkDeadline(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) throw new IndexFailure("TIMEOUT");
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
    private static final class IndexFailure extends RuntimeException {
        final String code;
        IndexFailure(String code) { this.code = code; }
    }
}
