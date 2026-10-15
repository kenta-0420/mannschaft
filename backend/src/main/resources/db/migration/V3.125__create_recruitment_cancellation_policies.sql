-- F03.11 募集型予約: キャンセルポリシー本体 (Phase 5a)
-- スナップショット方式: テンプレートからの複製時に DEEP COPY して新規レコード生成
CREATE TABLE recruitment_cancellation_policies (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type                  VARCHAR(20)     NOT NULL,
    scope_id                    BIGINT UNSIGNED NOT NULL,
    policy_name                 VARCHAR(100),
    free_until_hours_before     INT UNSIGNED    NOT NULL,
    is_template_policy          BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by                  BIGINT UNSIGNED NOT NULL,
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_rcp_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_rcp_scope (scope_type, scope_id, deleted_at),
    INDEX idx_rcp_template_flag (is_template_policy, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
