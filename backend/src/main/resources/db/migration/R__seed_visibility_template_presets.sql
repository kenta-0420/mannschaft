-- プリセット定義の更新用 Repeatable スクリプト
-- プリセット名・説明・ルールを変更した場合はこのファイルを修正する

-- PRESET_TRAINING_PARTNERS
INSERT INTO visibility_templates (owner_user_id, name, description, icon_emoji, is_system_preset, preset_key)
VALUES (NULL, '練習仲間', '自チームのフレンドチーム全員', '🏃', TRUE, 'PRESET_TRAINING_PARTNERS')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), icon_emoji = VALUES(icon_emoji);

-- PRESET_LOCAL_TEAMS
INSERT INTO visibility_templates (owner_user_id, name, description, icon_emoji, is_system_preset, preset_key)
VALUES (NULL, '地域のチーム', '自チーム＋地域属性が合致するチーム', '🏘️', TRUE, 'PRESET_LOCAL_TEAMS')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), icon_emoji = VALUES(icon_emoji);

-- PRESET_ALL_FRIENDS
INSERT INTO visibility_templates (owner_user_id, name, description, icon_emoji, is_system_preset, preset_key)
VALUES (NULL, '全フレンド', '全フレンドチームのメンバー', '👥', TRUE, 'PRESET_ALL_FRIENDS')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), icon_emoji = VALUES(icon_emoji);
