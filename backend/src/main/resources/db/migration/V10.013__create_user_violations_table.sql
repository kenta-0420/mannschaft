-- ユーザー違反累積テーブル
CREATE TABLE user_violations (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL,
    report_id       BIGINT UNSIGNED          NOT NULL,
    action_id       BIGINT UNSIGNED          NOT NULL,
    violation_type  VARCHAR(20)     NOT NULL,
    reason          VARCHAR(30)     NOT NULL,
    expires_at      DATETIME        NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_uv_user      FOREIGN KEY (user_id)   REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_uv_report    FOREIGN KEY (report_id)  REFERENCES content_reports (id),
    CONSTRAINT fk_uv_action    FOREIGN KEY (action_id)  REFERENCES report_actions (id),
    INDEX idx_uv_user  (user_id, is_active, created_at DESC),
    INDEX idx_uv_type  (violation_type, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
