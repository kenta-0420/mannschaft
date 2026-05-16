-- F09.17 Phase 11-a: ユーザー通報
-- 通報 3 件で自動 SUSPEND 判定。保持期間 3 年。
CREATE TABLE ad_user_reports (
    id                BINARY(16)      NOT NULL,
    campaign_id       BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    reporter_user_id  BIGINT UNSIGNED NULL     COMMENT '通報者 (退会時 NULL 化・FKなし)',
    channel_type      ENUM('ANNOUNCEMENT','EMAIL','PUSH','BANNER') NOT NULL COMMENT '通報元チャネル',
    reason_code       ENUM('OFFENSIVE','MISLEADING','SPAM','IRRELEVANT','OTHER') NOT NULL COMMENT '通報理由',
    comment           VARCHAR(500)    NULL     COMMENT '自由記述',
    status            ENUM('NEW','REVIEWING','RESOLVED','DISMISSED') NOT NULL DEFAULT 'NEW' COMMENT '対応状態',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_aur_camp_status (campaign_id, status),
    INDEX idx_aur_status_created (status, created_at),
    INDEX idx_aur_reporter (reporter_user_id),
    CONSTRAINT fk_aur_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 ユーザー通報';
