-- Presigned URL 発行履歴（重複制御・容量制限用）
CREATE TABLE pending_uploads (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    memo_id                   BIGINT        NOT NULL COMMENT 'FK -> quick_memos.id (ON DELETE CASCADE)',
    user_id                   BIGINT UNSIGNED NOT NULL COMMENT 'FK -> users.id (ON DELETE CASCADE)',
    s3_key                    VARCHAR(512)  NOT NULL UNIQUE,
    declared_size_bytes       INT           NOT NULL,
    content_type              VARCHAR(100)  NOT NULL,
    presigned_url_expires_at  DATETIME(6)  NOT NULL,
    confirmed_at              DATETIME(6)  NULL     COMMENT 'NULL = 未確認（孤立URL）',
    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pending_uploads_memo FOREIGN KEY (memo_id) REFERENCES quick_memos (id) ON DELETE CASCADE,
    CONSTRAINT fk_pending_uploads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Presigned URL発行履歴';

CREATE INDEX idx_pending_uploads_memo_active ON pending_uploads (memo_id, confirmed_at, presigned_url_expires_at);
CREATE INDEX idx_pending_uploads_user_created ON pending_uploads (user_id, created_at);
CREATE INDEX idx_pending_uploads_expires ON pending_uploads (presigned_url_expires_at, confirmed_at);
