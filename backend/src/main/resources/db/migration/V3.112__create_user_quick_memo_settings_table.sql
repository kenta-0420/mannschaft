-- ユーザーごとのポイっとメモ設定（リマインドデフォルト値）
CREATE TABLE user_quick_memo_settings (
    user_id              BIGINT       NOT NULL COMMENT 'PK / FK -> users.id (ON DELETE CASCADE)',
    reminder_enabled     TINYINT(1)   NOT NULL DEFAULT 0,
    default_offset_1_days INT         NULL     COMMENT '1枠目: 何日後か（1-90）',
    default_time_1       TIME         NULL     COMMENT '1枠目: 時刻（HH:00 or HH:30）',
    default_offset_2_days INT         NULL     COMMENT '2枠目: 何日後か（1-90）',
    default_time_2       TIME         NULL     COMMENT '2枠目: 時刻（HH:00 or HH:30）',
    default_offset_3_days INT         NULL     COMMENT '3枠目: 何日後か（1-90）',
    default_time_3       TIME         NULL     COMMENT '3枠目: 時刻（HH:00 or HH:30）',
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_uqms_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ユーザーごとのポイっとメモ設定';
