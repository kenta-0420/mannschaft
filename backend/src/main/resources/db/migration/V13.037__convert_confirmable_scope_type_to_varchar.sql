-- F04.10: confirmable_notifications 3 テーブルの scope_type を ENUM → VARCHAR(20) に変換
-- COMMITTEE を受け入れられるようにする
ALTER TABLE confirmable_notification_settings
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';

ALTER TABLE confirmable_notifications
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';

ALTER TABLE confirmable_notification_templates
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';
