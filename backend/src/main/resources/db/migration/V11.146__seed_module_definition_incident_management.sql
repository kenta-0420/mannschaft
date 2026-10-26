-- インシデント・メンテナンス管理 モジュール定義シードデータ
-- module_definitions テーブルが存在する場合のみ INSERT する
INSERT INTO module_definitions (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at) VALUES
('インシデント・メンテナンス管理', 'incident_management', 'インシデント管理・メンテナンススケジュール機能', 'OPTIONAL', 42, 0, 14, 1, NOW(), NOW());
