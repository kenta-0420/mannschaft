-- F04.5 コンテンツ通報テーブル
CREATE TABLE content_reports (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    target_type        VARCHAR(30)   NOT NULL,
    target_id          BIGINT        NOT NULL,
    reporter_type      VARCHAR(20)   NOT NULL,
    reporter_id        BIGINT        NOT NULL,
    reason             VARCHAR(20)   NOT NULL,
    description        VARCHAR(1000) NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    reviewed_by        BIGINT        NULL,
    review_note        VARCHAR(1000) NULL,
    identity_disclosed BOOLEAN       NOT NULL DEFAULT FALSE,
    resolved_at        DATETIME      NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_content_reports_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_content_reports_status (status, created_at),
    INDEX idx_content_reports_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
