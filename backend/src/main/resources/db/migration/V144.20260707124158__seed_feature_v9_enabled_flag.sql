-- F09.19.1: FEATURE_V9_ENABLED フラグを有効で seed する（正本 §5.2 V144.007）
-- 背景: このフラグはどの migration にも seed されておらず「行なし = false」で動いている。
-- §7.5 のゲートを導入した瞬間に全環境で広告枠が消灯するため、有効 = TRUE で seed する。
-- feature_flags の実 DDL（V10.003）に合わせて列は is_enabled。
-- ON DUPLICATE KEY UPDATE flag_key = flag_key で既存行（手動設定）は上書きしない。
INSERT INTO feature_flags (flag_key, is_enabled, description, created_at, updated_at)
VALUES ('FEATURE_V9_ENABLED', TRUE, 'F09 系広告機能（表示・サービング・用品ランキング）の有効化', NOW(), NOW())
ON DUPLICATE KEY UPDATE flag_key = flag_key;
