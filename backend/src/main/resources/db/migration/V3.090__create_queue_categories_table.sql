CREATE TABLE queue_categories (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type      VARCHAR(20)      NOT NULL,
    scope_id        BIGINT UNSIGNED  NOT NULL,
    name            VARCHAR(50)      NOT NULL,
    queue_mode      VARCHAR(10)      NOT NULL DEFAULT 'INDIVIDUAL',
    prefix_char     VARCHAR(5),
    max_queue_size  SMALLINT UNSIGNED NOT NULL DEFAULT 50,
    display_order   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME,

    PRIMARY KEY (id),
    INDEX idx_qcat_scope_order (scope_type, scope_id, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='順番待ちカテゴリ';
