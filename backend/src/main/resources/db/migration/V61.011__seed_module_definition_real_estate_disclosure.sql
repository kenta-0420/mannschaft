-- F09.14 重要事項説明書出力 モジュール定義シードデータ
-- 既存最大 module_number = 46（property_history / V61.005）。本機能は 47 を確保。
-- 法定書類関連かつ生成コスト高のため有料プラン限定（requires_paid_plan=1）。30日トライアルあり。
INSERT INTO module_definitions (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at) VALUES
('重要事項説明書出力', 'real_estate_disclosure', 'マンション売買時の重要事項説明書をPDF/Excel/Wordで出力', 'OPTIONAL', 47, 1, 30, 1, NOW(), NOW());
