-- 新ARの私有snapshotと命令履歴。四源配送テーブル・歴史bootstrapは後続別migration。
-- UUIDはUuidV7Entity、user_idはauthへのID参照のみでcross-domain FKを作らない。
CREATE TABLE reflection_recall_sessions (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    entry_id_type VARCHAR(8) NOT NULL,
    entry_source_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
    reward_week DATE NULL,
    status VARCHAR(20) NOT NULL,
    prompt_snapshot JSON NOT NULL,
    original_snapshot JSON NOT NULL,
    answers_json JSON NOT NULL,
    self_rating VARCHAR(20) NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_recall_session_owner (user_id, id),
    INDEX idx_recall_session_entry (user_id, entry_id_type, entry_source_id),
    CONSTRAINT chk_recall_session_counter CHECK (version >= 0 AND user_id > 0),
    CONSTRAINT chk_recall_session_entry_type CHECK (entry_id_type = 'UUID'),
    CONSTRAINT chk_recall_session_status CHECK (status IN ('STARTED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_recall_session_rating CHECK (self_rating IS NULL OR self_rating IN ('REMEMBERED', 'PARTIAL', 'FORGOT')),
    CONSTRAINT chk_recall_session_snapshots CHECK (JSON_TYPE(prompt_snapshot) = 'ARRAY' AND JSON_TYPE(original_snapshot) = 'OBJECT' AND JSON_TYPE(answers_json) = 'ARRAY'),
    CONSTRAINT chk_recall_session_completion CHECK (
        (status = 'STARTED' AND completed_at IS NULL AND cancelled_at IS NULL AND self_rating IS NULL AND reward_week IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND cancelled_at IS NULL AND self_rating IS NOT NULL AND reward_week IS NOT NULL)
        OR (status = 'CANCELLED' AND completed_at IS NULL AND cancelled_at IS NOT NULL AND self_rating IS NULL AND reward_week IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reflection_recall_commands (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    command_type VARCHAR(20) NOT NULL,
    body_hash BINARY(32) NOT NULL,
    result_json JSON NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recall_command UNIQUE (user_id, idempotency_key),
    INDEX idx_recall_command_session (session_id),
    CONSTRAINT chk_recall_command_owner CHECK (user_id > 0),
    CONSTRAINT chk_recall_command_type CHECK (command_type IN ('START', 'ANSWERS', 'COMPLETE', 'CANCEL')),
    CONSTRAINT chk_recall_command_result CHECK (JSON_TYPE(result_json) = 'OBJECT'),
    CONSTRAINT fk_recall_command_session FOREIGN KEY (session_id) REFERENCES reflection_recall_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
