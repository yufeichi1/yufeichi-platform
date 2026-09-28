-- Disable the public V2 credentials without modifying migration history.
UPDATE sys_user SET status = 0 WHERE id = 1 AND username = 'admin';

CREATE TABLE sys_bootstrap_state (
    id TINYINT NOT NULL PRIMARY KEY,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at DATETIME NULL
) ENGINE=InnoDB;
INSERT INTO sys_bootstrap_state (id, completed) VALUES (1, FALSE);
