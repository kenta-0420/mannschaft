-- F03.5 §11.1 自動割当の停止（戦役B-1）。
-- 時刻を見ない割当が二重割当を生むため、既定 OFF で seed する（方針転換 2026-09-09）。
INSERT INTO feature_flags (flag_key, is_enabled, description)
VALUES ('FEATURE_SHIFT_AUTO_ASSIGN_ENABLED', FALSE, 'シフト自動割当（2026-09-09 方針転換により既定 OFF）')
ON DUPLICATE KEY UPDATE description = VALUES(description);
