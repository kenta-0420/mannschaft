-- F05.6 汎用ワークフロー・承認エンジン: workflow_templates テーブル
CREATE TABLE workflow_templates (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    scope_type        VARCHAR(20)   NOT NULL,
    scope_id          BIGINT        NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    description       VARCHAR(500)  NULL,
    icon              VARCHAR(50)   NULL,
    color             VARCHAR(7)    NULL,
    is_seal_required  BOOLEAN       NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order        INT           NOT NULL DEFAULT 0,
    created_by        BIGINT        NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME      NULL,
    PRIMARY KEY (id),
    INDEX idx_workflow_templates_scope (scope_type, scope_id),
    INDEX idx_workflow_templates_active (is_active),
    INDEX idx_workflow_templates_created_by (created_by),
    CONSTRAINT fk_workflow_templates_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
