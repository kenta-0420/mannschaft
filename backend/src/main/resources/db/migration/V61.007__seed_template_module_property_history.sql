-- F09.13 物件履歴台帳 テンプレート×モジュール紐付けシードデータ
-- apartment テンプレート（template_id=10）に推奨紐付けを追加
-- 既存 V2.026 で apartment には voting/resident_register/parking/equipment が紐付け済み
INSERT INTO template_modules (template_id, module_id, created_at)
SELECT 10, id, NOW()
FROM module_definitions
WHERE slug = 'property_history';
