-- 異議申立てテーブル
CREATE TABLE moderation_appeals (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT UNSIGNED NOT NULL,
    report_id                BIGINT          NOT NULL,
    action_id                BIGINT          NOT NULL,
    appeal_token             VARCHAR(64)     NOT NULL,
    appeal_token_expires_at  DATETIME        NOT NULL,
    appeal_reason            TEXT            NULL,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'INVITED',
    reviewed_by              BIGINT UNSIGNED NULL,
    review_note              TEXT            NULL,
    reviewed_at              DATETIME        NULL,
    created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ma_user    FOREIGN KEY (user_id)   REFERENCES users (id),
    CONSTRAINT fk_ma_report  FOREIGN KEY (report_id) REFERENCES content_reports (id),
    CONSTRAINT fk_ma_action  FOREIGN KEY (action_id) REFERENCES report_actions (id),
    UNIQUE KEY uq_ma_token        (appeal_token),
    UNIQUE KEY uq_ma_user_action  (user_id, action_id),
    INDEX idx_ma_status (status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
