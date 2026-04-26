-- F03.12 ケア対象者見守り通知: 通知履歴テーブル（90日で自動削除対象）
CREATE TABLE event_care_notification_logs (
    id                         BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    event_id                   BIGINT UNSIGNED    NOT NULL COMMENT 'FK → events.id',
    care_recipient_user_id     BIGINT UNSIGNED    NOT NULL COMMENT 'ケア対象 users.id',
    watcher_user_id            BIGINT UNSIGNED    NOT NULL COMMENT '見守り者 users.id',
    care_category              ENUM('MINOR','ELDERLY','DISABILITY_SUPPORT','GENERAL_FAMILY') NOT NULL COMMENT 'このリンクのケア区分（送信時スナップショット）',
    notification_type          ENUM('RSVP_CONFIRMED','CHECKIN','CHECKOUT','NO_CONTACT_CHECK','ABSENT_ALERT','DISMISSAL') NOT NULL COMMENT '通知種別',
    notification_id            BIGINT             NULL COMMENT 'FK → notifications.id（配信レコード参照）',
    sent_at                    DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '送信日時',
    retry_count                TINYINT            NOT NULL DEFAULT 0 COMMENT '再送回数（最大3）',

    PRIMARY KEY (id),
    CONSTRAINT fk_ecnl_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    INDEX idx_ecnl_event    (event_id, care_recipient_user_id),
    INDEX idx_ecnl_watcher  (watcher_user_id, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ケア対象者見守り通知ログ（90日保持）';
