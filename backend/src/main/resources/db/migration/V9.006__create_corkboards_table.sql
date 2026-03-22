-- F09.8: コルクボード
CREATE TABLE corkboards (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type        VARCHAR(20)     NOT NULL,
    scope_id          BIGINT UNSIGNED NULL,
    owner_id          BIGINT UNSIGNED NULL,
    name              VARCHAR(100)    NOT NULL,
    background_style  VARCHAR(10)     NOT NULL DEFAULT 'CORK',
    edit_policy       VARCHAR(20)     NOT NULL DEFAULT 'ADMIN_ONLY',
    is_default        BOOLEAN         NOT NULL DEFAULT FALSE,
    version           BIGINT          NOT NULL DEFAULT 0,
    deleted_at        DATETIME        NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_corkboards_personal (owner_id, deleted_at),
    INDEX idx_corkboards_scope (scope_type, scope_id, deleted_at),
    CONSTRAINT fk_cb_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
