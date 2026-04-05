-- F09.6: ダイレクトメールテンプレート
CREATE TABLE direct_mail_templates (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type      VARCHAR(20)     NOT NULL,
    scope_id        BIGINT UNSIGNED NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    subject         VARCHAR(200)    NOT NULL,
    body_markdown   TEXT            NOT NULL,
    created_by      BIGINT UNSIGNED NOT NULL,
    deleted_at      DATETIME        NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_dmt_scope (scope_type, scope_id, deleted_at),
    CONSTRAINT fk_dmt_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
