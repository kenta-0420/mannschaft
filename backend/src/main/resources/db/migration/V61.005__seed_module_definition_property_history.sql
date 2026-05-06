-- F09.13 物件履歴台帳 モジュール定義シードデータ
-- 既存最大 module_number = 45（multilingual_content）。本機能は 46 を確保。
INSERT INTO module_definitions (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at) VALUES
('物件履歴台帳', 'property_history', 'マンション・施設の改修・修繕・事故・点検履歴を時系列で管理', 'OPTIONAL', 46, 0, 30, 1, NOW(), NOW());
