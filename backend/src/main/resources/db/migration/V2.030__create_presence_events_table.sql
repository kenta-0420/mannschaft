-- F01.4: 帰ったよ通知・お出かけ連絡のイベント履歴
CREATE TABLE presence_events (
    id                 BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id            BIGINT UNSIGNED  NOT NULL,
    user_id            BIGINT UNSIGNED  NOT NULL,
    event_type         VARCHAR(20)      NOT NULL COMMENT 'HOME / GOING_OUT',
    message            VARCHAR(100)     NULL     COMMENT 'ひとことメッセージ',
    destination        VARCHAR(200)     NULL     COMMENT 'お出かけ先（GOING_OUT時のみ）',
    expected_return_at DATETIME         NULL     COMMENT '帰宅予定時刻（GOING_OUT時のみ）',
    returned_at        DATETIME         NULL     COMMENT '実際の帰宅時刻',
    overdue_level      TINYINT          NOT NULL DEFAULT 0 COMMENT '遅延通知段階 0=未通知,1=15分超過,2=1時間超過',
    created_at         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pe_team_user    (team_id, user_id, created_at DESC),
    INDEX idx_pe_team_latest  (team_id, event_type, created_at DESC),
    INDEX idx_pe_overdue      (event_type, returned_at, overdue_level, expected_return_at),
    CONSTRAINT fk_pe_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_pe_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
