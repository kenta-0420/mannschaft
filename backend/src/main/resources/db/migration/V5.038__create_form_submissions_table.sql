-- F05.7 書類テンプレート・フォームビルダー: フォーム提出テーブル
CREATE TABLE form_submissions (
    id              BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    template_id     BIGINT UNSIGNED          NOT NULL,
    scope_type      VARCHAR(20)     NOT NULL,
    scope_id        BIGINT UNSIGNED          NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    submitted_by    BIGINT UNSIGNED NULL,
    workflow_request_id BIGINT UNSIGNED      NULL,
    pdf_file_key    VARCHAR(500),
    submission_count_for_user INT   NOT NULL DEFAULT 1,
    version         BIGINT UNSIGNED          NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_form_submissions_template FOREIGN KEY (template_id) REFERENCES form_templates(id) ON DELETE RESTRICT,
    CONSTRAINT fk_form_submissions_submitted_by FOREIGN KEY (submitted_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_form_submissions_template (template_id),
    INDEX idx_form_submissions_scope (scope_type, scope_id),
    INDEX idx_form_submissions_submitted_by (submitted_by),
    INDEX idx_form_submissions_status (status),
    INDEX idx_form_submissions_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
