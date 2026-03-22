-- F10.1 目安箱（フィードバック投稿）テーブル
CREATE TABLE feedback_submissions (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type         VARCHAR(20)     NOT NULL,
    scope_id           BIGINT UNSIGNED NULL,
    category           VARCHAR(20)     NOT NULL,
    title              VARCHAR(200)    NOT NULL,
    body               TEXT            NOT NULL,
    is_anonymous       BOOLEAN         NOT NULL DEFAULT FALSE,
    submitted_by       BIGINT UNSIGNED NOT NULL,
    status             VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    admin_response     TEXT            NULL,
    responded_by       BIGINT UNSIGNED NULL,
    responded_at       DATETIME        NULL,
    is_public_response BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_fs_submitted_by FOREIGN KEY (submitted_by) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_fs_scope (scope_type, scope_id, status, created_at DESC),
    INDEX idx_fs_submitted_by (submitted_by),
    INDEX idx_fs_category (scope_type, scope_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
