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
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sources = sources;
        this.vectors = vectors; this.properties = properties; this.guard = guard;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (available()) worker.scheduleWithFixedDelay(this::poll, 1, 2, TimeUnit.SECONDS); }
    private boolean available() { return properties.isEnabled() && properties.getVector().isEnabled() && vectors.getIfAvailable() != null; }
    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void changed(AiContentChanged event) {
        if (!available() || !properties.isIndexSyncEnabled()) return;
        try {
            // AFTER_COMMIT needs a new transaction: original transaction resources may still be bound.
            tx.executeWithoutResult(t -> jdbc.update("UPDATE ai_index_sync SET generation=generation+1,failed_attempts=0,last_error=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 SECOND),updated_at=UTC_TIMESTAMP(3) WHERE id=1"));
        } catch (RuntimeException signalFailure) {
            // The content commit has already succeeded. Five-minute reconciliation repairs a missed signal.
            LoggerFactory.getLogger(getClass()).warn("AI index change signal unavailable; reconciliation pending");
        }
    }
    private void reconcile() {
        if (!properties.isIndexSyncEnabled()) return;
        int due = jdbc.queryForObject("SELECT COUNT(*) FROM ai_index_sync WHERE id=1 AND (last_reconcile_at IS NULL OR last_reconcile_at <= DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE))", Integer.class);
        if (due == 0) return;
        AiKnowledgeSources.Snapshot snapshot;
        try { snapshot = sources.read(false); }
        catch (AiKnowledgeSources.IndexLimitException oversized) {
            // Still enqueue a visible INPUT_LIMIT result, rather than repeatedly failing every poll.
            tx.executeWithoutResult(t -> {
                jdbc.update("UPDATE ai_index_sync SET generation=generation+1,next_attempt_at=UTC_TIMESTAMP(3) WHERE id=1 AND generation=completed_generation");
                jdbc.update("UPDATE ai_index_sync SET last_reconcile_at=UTC_TIMESTAMP(3) WHERE id=1");
            });
            return;
        }
        String hash = jdbc.queryForObject("SELECT manifest_hash FROM ai_index_state WHERE id=1", String.class);
        tx.executeWithoutResult(t -> {
            jdbc.queryForMap("SELECT id FROM ai_index_state WHERE id=1 FOR UPDATE");
            jdbc.queryForMap("SELECT id FROM ai_index_sync WHERE id=1 FOR UPDATE");
            if (!snapshot.hash().equals(hash))
                jdbc.update("UPDATE ai_index_sync SET generation=generation+1,next_attempt_at=UTC_TIMESTAMP(3) WHERE id=1 AND generation=completed_generation");
            jdbc.update("UPDATE ai_index_sync SET last_reconcile_at=UTC_TIMESTAMP(3) WHERE id=1");
        });
        // Independent cleanup never calls an embedding provider. Current MySQL still gates every answer.
        try { vectors.getObject().pruneOwnedObsolete(jdbc.queryForObject("SELECT active_version FROM ai_index_state WHERE id=1", String.class), snapshot); }
        catch (RuntimeException unavailable) { LoggerFactory.getLogger(getClass()).warn("AI obsolete vector cleanup deferred"); }
    }
    private void queueAutomatic() {
        if (!properties.isIndexSyncEnabled()) return;
        tx.executeWithoutResult(t -> {
            var state = jdbc.queryForMap("SELECT running_job FROM ai_index_state WHERE id=1 FOR UPDATE");
            if (state.get("running_job") != null) return;
            var rows = jdbc.queryForList("SELECT generation FROM ai_index_sync WHERE id=1 AND generation>completed_generation AND failed_attempts<3 AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(3)) FOR UPDATE");
            if (!rows.isEmpty()) insertJob(0, "AUTO", ((Number) rows.getFirst().get("generation")).longValue());
        });
    }
    private String insertJob(long userId, String origin, long generation) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_index_job(id,requested_by,status,origin,sync_generation) VALUES(?,?,'QUEUED',?,?)", id, userId, origin, generation);
        jdbc.update("UPDATE ai_index_state SET running_job=? WHERE id=1", id);
        return id;
    }
    public Map<String, Object> status() {
        var state = jdbc.queryForMap("SELECT active_version,activated_at,running_job FROM ai_index_state WHERE id=1");
        var result = new LinkedHashMap<String, Object>(); result.put("available", available()); result.put("state", state);
        result.put("syncEnabled", properties.isIndexSyncEnabled());
        result.put("sync", jdbc.queryForMap("SELECT * FROM ai_index_sync WHERE id=1"));
        result.put("jobs", jdbc.queryForList("SELECT id,status,origin,index_version,source_count,chunk_count,completed_chunks,error_code,created_at,finished_at FROM ai_index_job ORDER BY created_at DESC,id DESC LIMIT 20"));
        return result;
    }
    public String submit(long userId) {
        if (!available()) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        return tx.execute(status -> {
            var state = jdbc.queryForMap("SELECT running_job FROM ai_index_state WHERE id=1 FOR UPDATE");
            if (state.get("running_job") != null) throw new BusinessException(ErrorCode.CONFLICT);
            long generation = jdbc.queryForObject("SELECT generation FROM ai_index_sync WHERE id=1 FOR UPDATE", Long.class);
            jdbc.update("UPDATE ai_index_sync SET failed_attempts=0,last_error=NULL,next_attempt_at=NULL WHERE id=1");
            return insertJob(userId, "MANUAL", generation);
        });
    }
    private void poll() {
        // MySQL connection-scoped mutex also prevents two app instances from running paid jobs.
        // A crashed connection releases it; durable generations recover with at most three attempts.
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            try (var lock = connection.prepareStatement("SELECT GET_LOCK('yufeichi-ai-index',0)")) {
                lock.setQueryTimeout(5);
                try (var row = lock.executeQuery()) { if (!row.next() || row.getInt(1) != 1) return; }
            }
            try {
                String id = jdbc.queryForObject("SELECT running_job FROM ai_index_state WHERE id=1", String.class);
                if (id == null) {
                    reconcile(); queueAutomatic();
                    id = jdbc.queryForObject("SELECT running_job FROM ai_index_state WHERE id=1", String.class);
                    if (id == null) return;
                }
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
            vector.pruneOwnedObsolete(jdbc.queryForObject("SELECT active_version FROM ai_index_state WHERE id=1", String.class), snapshot);
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
                Long generation = jdbc.queryForObject("SELECT sync_generation FROM ai_index_job WHERE id=?", Long.class, id);
                if (generation != null) {
                    jdbc.update("UPDATE ai_index_sync SET completed_generation=GREATEST(completed_generation,?) WHERE id=1", generation);
                    jdbc.update("UPDATE ai_index_sync SET failed_attempts=0,last_error=NULL,next_attempt_at=NULL WHERE id=1 AND generation=?", generation);
                }
            });
            try { vector.pruneOwnedObsolete(completeVersion, sources.read(false)); }
            catch (RuntimeException unavailable) { LoggerFactory.getLogger(getClass()).warn("AI obsolete vector cleanup deferred"); }
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
            jdbc.queryForMap("SELECT id FROM ai_index_state WHERE id=1 FOR UPDATE");
            int changed = jdbc.update("UPDATE ai_index_job SET status='FAILED',error_code=?,finished_at=NOW() WHERE id=? AND status IN ('RUNNING','QUEUED')", code, id);
            jdbc.update("UPDATE ai_index_state SET running_job=NULL WHERE id=1 AND running_job=?", id);
            if (changed == 1) {
                Long generation = jdbc.queryForObject("SELECT sync_generation FROM ai_index_job WHERE id=?", Long.class, id);
                // Legacy jobs can have no generation. Newer content must not inherit an older failure.
                if (generation != null) {
                    jdbc.queryForMap("SELECT id FROM ai_index_sync WHERE id=1 FOR UPDATE");
                    jdbc.update("UPDATE ai_index_sync SET failed_attempts=IF(? IN ('INPUT_LIMIT','QUOTA'),3,LEAST(failed_attempts+1,3)),last_error=?,next_attempt_at=TIMESTAMPADD(SECOND,? * POW(2,failed_attempts-1),UTC_TIMESTAMP(3)) WHERE id=1 AND generation=?",
                            code, code, properties.getIndexRetryDelaySeconds(), generation);
                }
            }
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
