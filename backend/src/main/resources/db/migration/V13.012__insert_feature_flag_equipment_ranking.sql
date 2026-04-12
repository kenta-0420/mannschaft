INSERT INTO feature_flags (flag_key, is_enabled, description)
VALUES ('FEATURE_EQUIPMENT_RANKING_ENABLED', FALSE, '同類チーム備品ランキング・レコメンド機能')
ON DUPLICATE KEY UPDATE description = VALUES(description);
