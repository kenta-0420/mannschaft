-- 旧回答は本人/代理を推定せずUNKNOWN履歴とする。新行の初回本人証拠だけを本体UPDATEへ固定。
ALTER TABLE schedule_attendances
 ADD COLUMN is_ranch_response_history_known BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN is_ranch_self_response_observed BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN ranch_first_self_at DATETIME(6) NULL,
 ADD COLUMN ranch_first_self_user_id BIGINT UNSIGNED NULL,
 ADD CONSTRAINT ck_schedule_ranch_native_metadata CHECK (
 (ranch_first_self_at IS NULL AND ranch_first_self_user_id IS NULL)
 OR (ranch_first_self_at IS NOT NULL AND ranch_first_self_user_id IS NOT NULL
 AND is_ranch_response_history_known=TRUE AND is_ranch_self_response_observed=TRUE));
CREATE TABLE schedule_ranch_witnesses (
 id BINARY(16) PRIMARY KEY, source_id_type VARCHAR(8) NOT NULL,
 canonical_source_id VARBINARY(80) NOT NULL, recipient_user_id BIGINT UNSIGNED NOT NULL,
 schedule_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(20) NOT NULL, qualifying_at DATETIME(6) NULL, event_id BINARY(16) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_schedule_ranch_witness (source_id_type,canonical_source_id),
 UNIQUE KEY uk_schedule_ranch_first_self (schedule_id,recipient_user_id),
 KEY idx_schedule_ranch_recipient (recipient_user_id,qualifying_at),
 CHECK (kind IN ('HISTORICAL','QUALIFIED')),
 CHECK (kind='HISTORICAL' OR (qualifying_at IS NOT NULL AND event_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE schedule_ranch_outboxes (
 id BINARY(16) PRIMARY KEY, schema_version INT NOT NULL, event_type VARCHAR(40) NOT NULL,
 scope_type VARCHAR(20) NOT NULL, scope_id_type VARCHAR(8) NULL,
 canonical_scope_id VARBINARY(80) NULL, recipient_user_id BIGINT UNSIGNED NOT NULL,
 canonical_key VARBINARY(240) NOT NULL, payload_json JSON NOT NULL,
 occurred_at DATETIME(6) NOT NULL, status VARCHAR(20) NOT NULL,
 terminal_outcome VARCHAR(40) NULL, attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL, lease_token BINARY(16) NULL,
 lease_expires_at DATETIME(6) NULL, last_error_code VARCHAR(80) NULL, acked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_schedule_ranch_event (event_type,canonical_key),
 KEY idx_schedule_ranch_pending (status,next_attempt_at,id),
 KEY idx_schedule_ranch_scope (scope_type,canonical_scope_id,status,next_attempt_at,id),
 KEY idx_schedule_ranch_user (recipient_user_id,id),
 CHECK (attempt_count>=0),
 CHECK (status IN ('PENDING','LEASED','RETRY','ACKED','DEAD_LETTER')),
 CHECK (scope_type IN ('PERSONAL','TEAM','ORGANIZATION')),
 CHECK (scope_type<>'PERSONAL' OR (scope_id_type IS NULL AND canonical_scope_id IS NULL)),
 CHECK (status<>'ACKED' OR (terminal_outcome IS NOT NULL AND acked_at IS NOT NULL)),
 CHECK (status<>'LEASED' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE schedule_ranch_admin_commands (
 id BINARY(16) PRIMARY KEY, actor_user_id BIGINT UNSIGNED NOT NULL, idempotency_key BINARY(16) NOT NULL,
 command_type VARCHAR(30) NOT NULL, body_hash BINARY(32) NOT NULL, result_json JSON NOT NULL,
 completed_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_schedule_ranch_admin_command (actor_user_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- 技術witness UUIDv7はUTC実行時刻と既存一意LONGの64bitから正準wireを生成する。
-- 既存回答は全てHISTORICAL。将来の編集・公開でも過去資格を捏造しない。
INSERT INTO schedule_ranch_witnesses
 (id,source_id_type,canonical_source_id,recipient_user_id,schedule_id,kind,qualifying_at,event_id,created_at,updated_at)
SELECT UNHEX(CONCAT(LPAD(HEX(FLOOR(TIMESTAMPDIFF(MICROSECOND,CAST('1970-01-01 00:00:00' AS DATETIME),UTC_TIMESTAMP(3))/1000)),12,'0'),'7',
 LPAD(HEX(id >> 60),3,'0'),'8',LPAD(HEX(id & 1152921504606846975),15,'0'))),
 'LONG',CAST(id AS CHAR),user_id,schedule_id,'HISTORICAL',NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM (SELECT MIN(id) AS id,user_id,schedule_id FROM schedule_attendances
 WHERE user_id IS NOT NULL GROUP BY schedule_id,user_id) AS historical;
