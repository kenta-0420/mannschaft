-- F05.6 汎用ワークフロー・承認エンジン: workflow_request_attachments テーブル
CREATE TABLE workflow_request_attachments (
    id                  BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    request_id          BIGINT UNSIGNED        NOT NULL,
    file_key            VARCHAR(500)  NOT NULL,
    original_filename   VARCHAR(255)  NOT NULL,
    file_size           BIGINT UNSIGNED        NOT NULL,
    uploaded_by         BIGINT UNSIGNED NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_wf_request_attachments_request (request_id),
    INDEX idx_wf_request_attachments_uploaded_by (uploaded_by),
    CONSTRAINT fk_wf_request_attachments_request FOREIGN KEY (request_id) REFERENCES workflow_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_wf_request_attachments_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
