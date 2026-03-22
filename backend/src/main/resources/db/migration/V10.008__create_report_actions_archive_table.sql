-- F10.1 通報対応アクションアーカイブテーブル
CREATE TABLE report_actions_archive (
    id                 BIGINT        NOT NULL,
    report_id          BIGINT        NOT NULL,
    action_type        VARCHAR(20)   NOT NULL,
    action_by          BIGINT UNSIGNED NOT NULL,
    note               TEXT          NULL,
    freeze_until       DATETIME      NULL,
    guideline_section  VARCHAR(100)  NULL,
    created_at         DATETIME      NOT NULL,
    archived_at        DATETIME      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_raa_archived (archived_at),
    INDEX idx_raa_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
