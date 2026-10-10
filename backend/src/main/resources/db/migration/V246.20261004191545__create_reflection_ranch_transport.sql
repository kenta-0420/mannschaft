-- reflection本人想起だけの源配送。V243の私有本体表は再定義しない。
CREATE TABLE reflection_ranch_witnesses (
 id BINARY(16) NOT NULL PRIMARY KEY,
 source_id_type VARCHAR(8) NOT NULL,
 canonical_source_id VARBINARY(80) NOT NULL,
 recipient_user_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(20) NOT NULL,
 qualifying_at DATETIME(6) NULL,
 event_id BINARY(16) NULL,
 reward_week DATE NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_reflection_ranch_witness (recipient_user_id,source_id_type,canonical_source_id,reward_week),
 KEY idx_reflection_ranch_recipient (recipient_user_id,id),
 CHECK (recipient_user_id>0 AND source_id_type='UUID'),
 CHECK (kind IN ('HISTORICAL','QUALIFIED')),
 CHECK ((kind='HISTORICAL' AND qualifying_at IS NULL AND event_id IS NULL)
     OR (kind='QUALIFIED' AND qualifying_at IS NOT NULL AND event_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reflection_ranch_outboxes (
 id BINARY(16) NOT NULL PRIMARY KEY,
 schema_version INT NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 scope_type VARCHAR(20) NOT NULL,
 scope_id_type VARCHAR(8) NULL,
 canonical_scope_id VARBINARY(80) NULL,
 recipient_user_id BIGINT UNSIGNED NOT NULL,
 canonical_key VARBINARY(240) NOT NULL,
 payload_json JSON NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 status VARCHAR(20) NOT NULL,
 terminal_outcome VARCHAR(40) NULL,
 attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL,
 lease_token BINARY(16) NULL,
 lease_expires_at DATETIME(6) NULL,
 last_error_code VARCHAR(80) NULL,
 acked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_reflection_ranch_event (event_type,canonical_key),
 KEY idx_reflection_ranch_pending (status,next_attempt_at,id),
 KEY idx_reflection_ranch_user (recipient_user_id,id),
 CHECK (schema_version=1 AND recipient_user_id>0 AND attempt_count>=0),
 CHECK (event_type='PERSONAL_RECALL_COMPLETE' AND scope_type='PERSONAL'
     AND scope_id_type IS NULL AND canonical_scope_id IS NULL),
 CHECK (JSON_TYPE(payload_json)='OBJECT'),
 CHECK (status IN ('PENDING','LEASED','RETRY','ACKED','DEAD_LETTER')),
 CHECK (status<>'ACKED' OR (terminal_outcome IS NOT NULL AND acked_at IS NOT NULL)),
 CHECK (status<>'LEASED' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reflection_ranch_admin_commands (
 id BINARY(16) NOT NULL PRIMARY KEY,
 actor_user_id BIGINT UNSIGNED NOT NULL,
 idempotency_key BINARY(16) NOT NULL,
 command_type VARCHAR(30) NOT NULL,
 body_hash BINARY(32) NOT NULL,
 result_json JSON NOT NULL,
 completed_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_reflection_ranch_admin_command (actor_user_id,idempotency_key),
 CHECK (actor_user_id>0 AND command_type='OUTBOX_RETRY'),
 CHECK (JSON_TYPE(result_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- V243に既存の完了は遡及報酬にしない。IDは既存UuidV7セッションの最小IDを再利用する。
-- legacy単発recallは新ARのセッション完了証拠を持たず、配送producerへ接続しない。
INSERT INTO reflection_ranch_witnesses
 (id,source_id_type,canonical_source_id,recipient_user_id,kind,qualifying_at,event_id,reward_week,created_at,updated_at)
SELECT MIN(id),'UUID',CAST(entry_source_id AS BINARY),user_id,'HISTORICAL',NULL,NULL,reward_week,
 UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM reflection_recall_sessions WHERE status='COMPLETED'
GROUP BY user_id,entry_source_id,reward_week;
