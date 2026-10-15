CREATE TABLE storage_migration_errors (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reference_type VARCHAR(50) NOT NULL COMMENT '対象テーブル名（例: chat_message_attachments）',
    reference_id BIGINT UNSIGNED NOT NULL COMMENT '対象レコードID',
    old_file_key VARCHAR(1000) NOT NULL COMMENT '移行前R2キー',
    new_file_key VARCHAR(1000) NOT NULL COMMENT '移行先R2キー',
    error_message TEXT COMMENT 'エラー内容',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'リトライ回数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME COMMENT '解決日時（手動マーク用）',
    PRIMARY KEY (id),
    INDEX idx_sme_reference (reference_type, reference_id),
    INDEX idx_sme_resolved (resolved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ストレージパス移行エラー記録';
