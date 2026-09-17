-- Sample owner prefix iup_; actual names resolved by PlatformOutputStore.
-- Exported from OutputSql; not a manual production migration/activation script.
CREATE TABLE IF NOT EXISTS iup_schema (component VARCHAR(32) PRIMARY KEY, version INTEGER NOT NULL) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS iup_outputs (attempt_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, plan_digest VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, payload LONGBLOB, payload_digest VARCHAR(64), reason VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS iup_output_ids (token_digest VARCHAR(64) PRIMARY KEY, attempt_id VARCHAR(36) NOT NULL, role VARCHAR(16) NOT NULL) ENGINE=InnoDB;
