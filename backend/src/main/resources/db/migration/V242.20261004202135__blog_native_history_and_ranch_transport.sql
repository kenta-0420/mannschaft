-- V242 native追加候補。既存の全記事は過去痕跡によらず保守的にHISTORICAL。
ALTER TABLE blog_posts
 ADD COLUMN ranch_publication_historical BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN ranch_publication_observed BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN first_published_at DATETIME(6) NULL,
 ADD COLUMN first_published_author_user_id BIGINT UNSIGNED NULL,
 ADD COLUMN publication_history_known BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE blog_posts SET ranch_publication_historical=TRUE, ranch_publication_observed=TRUE;
ALTER TABLE blog_posts ADD CONSTRAINT ck_blog_native_publication
 CHECK (ranch_publication_historical=FALSE OR ranch_publication_observed=TRUE),
 ADD CONSTRAINT ck_blog_native_publication_metadata
 CHECK ((first_published_at IS NULL AND first_published_author_user_id IS NULL)
 OR (first_published_at IS NOT NULL AND first_published_author_user_id IS NOT NULL
 AND publication_history_known=TRUE AND ranch_publication_observed=TRUE));

-- 四源正本02の技術配送表。他domainへのFKと本文payloadは追加しない。
CREATE TABLE blog_ranch_witnesses (
 id BINARY(16) PRIMARY KEY, source_id_type VARCHAR(8) NOT NULL,
 canonical_source_id VARBINARY(80) NOT NULL, recipient_user_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(20) NOT NULL, qualifying_at DATETIME(6) NULL, event_id BINARY(16) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_blog_ranch_witness (source_id_type,canonical_source_id),
 KEY idx_blog_ranch_recipient (recipient_user_id,qualifying_at),
 CHECK (kind IN ('HISTORICAL','QUALIFIED')),
 CHECK (kind='HISTORICAL' OR (qualifying_at IS NOT NULL AND event_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE blog_ranch_outboxes (
 id BINARY(16) PRIMARY KEY, schema_version INT NOT NULL, event_type VARCHAR(40) NOT NULL,
 scope_type VARCHAR(20) NOT NULL, scope_id_type VARCHAR(8) NULL,
 canonical_scope_id VARBINARY(80) NULL, recipient_user_id BIGINT UNSIGNED NOT NULL,
 canonical_key VARBINARY(240) NOT NULL, payload_json JSON NOT NULL,
 occurred_at DATETIME(6) NOT NULL, status VARCHAR(20) NOT NULL,
 terminal_outcome VARCHAR(40) NULL, attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL, lease_token BINARY(16) NULL,
 lease_expires_at DATETIME(6) NULL, last_error_code VARCHAR(80) NULL, acked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_blog_ranch_event (event_type,canonical_key),
 KEY idx_blog_ranch_pending (status,next_attempt_at,id),
 KEY idx_blog_ranch_scope (scope_type,canonical_scope_id,status,next_attempt_at,id),
 KEY idx_blog_ranch_user (recipient_user_id,id),
 CHECK (attempt_count>=0),
 CHECK (status IN ('PENDING','LEASED','RETRY','ACKED','DEAD_LETTER')),
 CHECK (scope_type IN ('PERSONAL','TEAM','ORGANIZATION')),
 CHECK (scope_type<>'PERSONAL' OR (scope_id_type IS NULL AND canonical_scope_id IS NULL)),
 CHECK (status<>'ACKED' OR (terminal_outcome IS NOT NULL AND acked_at IS NOT NULL)),
 CHECK (status<>'LEASED' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE blog_ranch_admin_commands (
 id BINARY(16) PRIMARY KEY, actor_user_id BIGINT UNSIGNED NOT NULL, idempotency_key BINARY(16) NOT NULL,
 command_type VARCHAR(30) NOT NULL, body_hash BINARY(32) NOT NULL, result_json JSON NOT NULL,
 completed_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_blog_ranch_admin_command (actor_user_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
