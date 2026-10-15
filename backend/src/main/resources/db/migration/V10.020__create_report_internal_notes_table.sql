-- 通報内部メモテーブル
CREATE TABLE report_internal_notes (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id   BIGINT UNSIGNED NOT NULL,
    author_id   BIGINT UNSIGNED NOT NULL,
    note        TEXT            NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rin_report FOREIGN KEY (report_id) REFERENCES content_reports (id) ON DELETE CASCADE,
    CONSTRAINT fk_rin_author FOREIGN KEY (author_id) REFERENCES users (id),
    INDEX idx_rin_report (report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
