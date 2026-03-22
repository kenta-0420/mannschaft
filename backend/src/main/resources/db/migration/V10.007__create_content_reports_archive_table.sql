-- F10.1 コンテンツ通報アーカイブテーブル
CREATE TABLE content_reports_archive (
    id                 BIGINT        NOT NULL,
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
    created_at         DATETIME      NOT NULL,
    archived_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cra_archived (archived_at),
    INDEX idx_cra_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
