-- F10.1 通報対応アクション履歴テーブル
CREATE TABLE report_actions (
    id                 BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    report_id          BIGINT UNSIGNED        NOT NULL,
    action_type        VARCHAR(20)   NOT NULL,
    action_by          BIGINT UNSIGNED NOT NULL,
    note               TEXT          NULL,
    freeze_until       DATETIME      NULL,
    guideline_section  VARCHAR(100)  NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_report_actions_report FOREIGN KEY (report_id) REFERENCES content_reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_actions_user FOREIGN KEY (action_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_ra_report (report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
