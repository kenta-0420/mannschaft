-- F09.2 プロモーション配信: セグメントプリセットテーブル
CREATE TABLE saved_segment_presets (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(20)         NOT NULL,
    scope_id            BIGINT UNSIGNED     NOT NULL,
    name                VARCHAR(100)        NOT NULL,
    conditions          JSON                NOT NULL,
    created_by          BIGINT UNSIGNED     NOT NULL,
    deleted_at          DATETIME,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_ssp_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_ssp_scope (scope_type, scope_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='セグメントプリセット';
