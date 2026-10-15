-- F03.11 募集型予約: リマインド通知テーブル (Phase 2)
-- 確定参加者へのリマインド通知設定・送信履歴
CREATE TABLE recruitment_reminders (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    listing_id      BIGINT UNSIGNED NOT NULL,
    participant_id  BIGINT UNSIGNED NOT NULL,
    remind_at       DATETIME        NOT NULL,
    sent_at         DATETIME        NULL,
    notification_id BIGINT UNSIGNED NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rr_pending (sent_at, remind_at),
    CONSTRAINT fk_rr_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_rr_participant
        FOREIGN KEY (participant_id) REFERENCES recruitment_participants (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- fk_rr_notification は V4.019 で notifications テーブルが作成された後に追加する（版序の関係で V3.123 では追加不可）
