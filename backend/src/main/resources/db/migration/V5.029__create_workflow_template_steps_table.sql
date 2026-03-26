-- F05.6 汎用ワークフロー・承認エンジン: workflow_template_steps テーブル
CREATE TABLE workflow_template_steps (
    id                BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    template_id       BIGINT UNSIGNED        NOT NULL,
    step_order        TINYINT       NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    approval_type     VARCHAR(10)   NOT NULL DEFAULT 'ALL',
    approver_type     VARCHAR(10)   NOT NULL,
    approver_user_ids JSON          NULL,
    approver_role     VARCHAR(30)   NULL,
    auto_approve_days SMALLINT      NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_wf_template_steps_template (template_id),
    CONSTRAINT fk_wf_template_steps_template FOREIGN KEY (template_id) REFERENCES workflow_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
