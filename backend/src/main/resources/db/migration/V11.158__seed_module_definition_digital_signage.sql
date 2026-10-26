-- デジタルサイネージ モジュール定義シードデータ
-- ※ module_definitions テーブルが存在しない環境ではこのマイグレーションはスキップされます
INSERT INTO module_definitions (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at) VALUES
('デジタルサイネージ', 'digital_signage', 'デジタルサイネージ管理機能', 'OPTIONAL', 44, 0, 14, 1, NOW(), NOW());
