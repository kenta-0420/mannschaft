-- F03.4+ 臨時休業一括通知: 臨時休業履歴テーブル
CREATE TABLE emergency_closures (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id              BIGINT UNSIGNED NOT NULL,
    start_date           DATE            NOT NULL,
    end_date             DATE            NOT NULL,
    reason               VARCHAR(200)    NOT NULL,
    subject              VARCHAR(200)    NOT NULL,
    message_body         TEXT            NOT NULL,
    sent_count           INT             NOT NULL DEFAULT 0,
    cancel_reservations  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by           BIGINT UNSIGNED NOT NULL,
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_emergency_closures_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    INDEX idx_emergency_closures_team_date (team_id, start_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
