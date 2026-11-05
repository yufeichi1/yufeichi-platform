-- Additive durable change generation; no modification of existing migrations.
CREATE TABLE ai_index_sync (
    id TINYINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL DEFAULT 0,
    completed_generation BIGINT NOT NULL DEFAULT 0,
    failed_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    last_error VARCHAR(32) NULL,
    last_reconcile_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
INSERT INTO ai_index_sync(id) VALUES(1);
ALTER TABLE ai_index_job ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN sync_generation BIGINT NULL;
