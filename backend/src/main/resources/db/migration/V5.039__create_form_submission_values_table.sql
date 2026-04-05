-- F05.7 書類テンプレート・フォームビルダー: フォーム提出値テーブル
CREATE TABLE form_submission_values (
    id              BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    submission_id   BIGINT UNSIGNED          NOT NULL,
    field_key       VARCHAR(50)     NOT NULL,
    field_type      VARCHAR(20)     NOT NULL,
    text_value      TEXT,
    number_value    DECIMAL(15,4),
    date_value      DATE,
    file_key        VARCHAR(500),
    is_auto_filled  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_form_submission_values_submission FOREIGN KEY (submission_id) REFERENCES form_submissions(id) ON DELETE CASCADE,
    INDEX idx_form_submission_values_submission (submission_id),
    INDEX idx_form_submission_values_field_key (field_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
