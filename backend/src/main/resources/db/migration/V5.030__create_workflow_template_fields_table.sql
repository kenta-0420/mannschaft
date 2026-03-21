-- F05.6 汎用ワークフロー・承認エンジン: workflow_template_fields テーブル
CREATE TABLE workflow_template_fields (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    template_id       BIGINT        NOT NULL,
    field_key         VARCHAR(50)   NOT NULL,
    field_label       VARCHAR(100)  NOT NULL,
    field_type        VARCHAR(20)   NOT NULL,
    is_required       BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order        INT           NOT NULL DEFAULT 0,
    options_json      JSON          NULL,
    PRIMARY KEY (id),
    INDEX idx_wf_template_fields_template (template_id),
    CONSTRAINT fk_wf_template_fields_template FOREIGN KEY (template_id) REFERENCES workflow_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
