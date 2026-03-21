-- F05.6 汎用ワークフロー・承認エンジン: workflow_request_approvers テーブル
CREATE TABLE workflow_request_approvers (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    request_step_id     BIGINT        NOT NULL,
    approver_user_id    BIGINT        NOT NULL,
    decision            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    decision_at         DATETIME      NULL,
    decision_comment    VARCHAR(1000) NULL,
    seal_id             BIGINT        NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_wf_request_approvers_step (request_step_id),
    INDEX idx_wf_request_approvers_user (approver_user_id),
    CONSTRAINT fk_wf_request_approvers_step FOREIGN KEY (request_step_id) REFERENCES workflow_request_steps(id) ON DELETE CASCADE,
    CONSTRAINT fk_wf_request_approvers_user FOREIGN KEY (approver_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
