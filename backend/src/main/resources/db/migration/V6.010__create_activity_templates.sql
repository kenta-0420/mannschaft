-- F06.4: 活動記録テンプレートテーブル
CREATE TABLE activity_templates (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type              VARCHAR(20) NOT NULL,
    scope_id                BIGINT UNSIGNED NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500) NULL,
    icon                    VARCHAR(30) NULL,
    color                   VARCHAR(7) NULL,
    is_participant_required BOOLEAN NOT NULL DEFAULT TRUE,
    default_visibility      VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY',
    sort_order              INT NOT NULL DEFAULT 0,
    created_by              BIGINT UNSIGNED NOT NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_at_scope (scope_type, scope_id, sort_order),
    CONSTRAINT fk_at_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
