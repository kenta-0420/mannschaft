-- F04.9: 確認通知設定テーブル（スコープごとのデフォルト設定）
CREATE TABLE confirmable_notification_settings (
    id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type                      ENUM('TEAM', 'ORGANIZATION') NOT NULL,
    scope_id                        BIGINT UNSIGNED NOT NULL,
    default_first_reminder_minutes  INT NULL              COMMENT '1回目リマインド送信タイミング（分）。NULLはリマインドなし',
    default_second_reminder_minutes INT NULL              COMMENT '2回目リマインド送信タイミング（分）。NULLはリマインドなし',
    sender_alert_threshold_percent  INT NOT NULL DEFAULT 80 COMMENT '送信者へのアラート閾値（確認率%）',
    created_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cns_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='確認通知設定';
