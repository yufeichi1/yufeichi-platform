-- Additive only. Business content and V1-V10 migration history remain untouched.
CREATE TABLE ai_index_job (
    id CHAR(36) NOT NULL PRIMARY KEY,
    requested_by BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    index_version CHAR(36) NULL,
    manifest_hash CHAR(64) NULL,
    source_count INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    completed_chunks INT NOT NULL DEFAULT 0,
    error_code VARCHAR(32) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    finished_at DATETIME NULL
) ENGINE=InnoDB;

CREATE TABLE ai_index_state (
    id TINYINT NOT NULL PRIMARY KEY,
    active_version CHAR(36) NULL,
    manifest_hash CHAR(64) NULL,
    running_job CHAR(36) NULL,
    activated_at DATETIME NULL
) ENGINE=InnoDB;
INSERT INTO ai_index_state(id) VALUES(1);
