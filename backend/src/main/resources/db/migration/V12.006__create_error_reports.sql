-- F12.5: フロントエンドエラー追跡 - error_reports テーブル作成
CREATE TABLE error_reports (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    error_message       VARCHAR(1000)   NOT NULL,
    stack_trace         VARCHAR(2000)   NULL,
    page_url            VARCHAR(2048)   NOT NULL,
    user_agent          VARCHAR(500)    NULL,
    user_comment        VARCHAR(1000)   NULL,
    user_id             BIGINT UNSIGNED NULL,
    organization_id     BIGINT UNSIGNED NULL,
    request_id          VARCHAR(36)     NULL,
    ip_address          VARCHAR(45)     NULL,
    occurred_at         DATETIME        NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'NEW',
    severity            VARCHAR(10)     NOT NULL DEFAULT 'MEDIUM',
    resolved_by         BIGINT UNSIGNED NULL,
    resolved_at         DATETIME        NULL,
    admin_note          VARCHAR(2000)   NULL,
    latest_user_comment VARCHAR(1000)   NULL,
    error_hash          VARCHAR(64)     NOT NULL,
    occurrence_count    INT             NOT NULL DEFAULT 1,
    affected_user_count INT             NOT NULL DEFAULT 1,
    first_occurred_at   DATETIME        NOT NULL,
    last_occurred_at    DATETIME        NOT NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_error_reports_user_id
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_error_reports_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL,
    CONSTRAINT fk_error_reports_resolved_by
        FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL
);

-- インデックス
CREATE INDEX idx_error_reports_status ON error_reports(status);
CREATE INDEX idx_error_reports_severity ON error_reports(severity);
CREATE INDEX idx_error_reports_error_hash ON error_reports(error_hash);
CREATE INDEX idx_error_reports_user_id ON error_reports(user_id);
CREATE INDEX idx_error_reports_last_occurred ON error_reports(last_occurred_at DESC);
CREATE INDEX idx_error_reports_request_id ON error_reports(request_id);
