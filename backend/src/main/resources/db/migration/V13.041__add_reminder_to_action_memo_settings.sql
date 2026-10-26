-- F02.5 Phase 4-β: user_action_memo_settings にリマインド設定カラムを追加
ALTER TABLE user_action_memo_settings
    ADD COLUMN reminder_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER default_category,
    ADD COLUMN reminder_time    TIME        NULL DEFAULT NULL AFTER reminder_enabled;
