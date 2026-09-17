-- Generated from JournalSql (test prefix iup_). Not a live Platform migration script.
-- Runtime must pass provider-generated table names and initialize one writer only.
CREATE TABLE IF NOT EXISTS iup_tx_schema (component VARCHAR(32) PRIMARY KEY,version INTEGER NOT NULL,updated_at BIGINT NOT NULL) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS iup_attempts (tx_id VARCHAR(36) PRIMARY KEY,idempotency_key VARCHAR(128) NOT NULL UNIQUE,player_uuid VARCHAR(36) NOT NULL,plan_digest VARCHAR(64) NOT NULL,plan_payload LONGBLOB NOT NULL,state VARCHAR(32) NOT NULL,version BIGINT NOT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,sample BIGINT,payload LONGBLOB NOT NULL,payload_digest VARCHAR(64) NOT NULL) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS iup_player_locks (player_uuid VARCHAR(36) PRIMARY KEY,tx_id VARCHAR(36) NOT NULL UNIQUE) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS iup_tx_events (tx_id VARCHAR(36) NOT NULL,version BIGINT NOT NULL,state VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,payload_digest VARCHAR(64) NOT NULL,PRIMARY KEY(tx_id,version)) ENGINE=InnoDB;

CREATE INDEX iup_attempts_player_time ON iup_attempts (player_uuid,created_at,tx_id);

CREATE INDEX iup_attempts_state_time ON iup_attempts (state,updated_at,tx_id);
