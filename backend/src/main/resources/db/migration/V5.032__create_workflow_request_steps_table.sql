-- F05.6 汎用ワークフロー・承認エンジン: workflow_request_steps テーブル
CREATE TABLE workflow_request_steps (
    id                BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    request_id        BIGINT UNSIGNED        NOT NULL,
    step_order        TINYINT       NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'WAITING',
    completed_at      DATETIME      NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_wf_request_steps_request (request_id),
    CONSTRAINT fk_wf_request_steps_request FOREIGN KEY (request_id) REFERENCES workflow_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
