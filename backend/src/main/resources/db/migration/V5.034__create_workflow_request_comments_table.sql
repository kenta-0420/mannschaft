-- F05.6 汎用ワークフロー・承認エンジン: workflow_request_comments テーブル
CREATE TABLE workflow_request_comments (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    request_id        BIGINT        NOT NULL,
    user_id           BIGINT        NULL,
    body              VARCHAR(2000) NOT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME      NULL,
    PRIMARY KEY (id),
    INDEX idx_wf_request_comments_request (request_id),
    INDEX idx_wf_request_comments_user (user_id),
    CONSTRAINT fk_wf_request_comments_request FOREIGN KEY (request_id) REFERENCES workflow_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_wf_request_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
