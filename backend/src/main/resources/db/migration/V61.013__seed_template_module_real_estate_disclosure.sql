-- F09.14 重要事項説明書出力 テンプレート×モジュール紐付けシードデータ
-- apartment テンプレート（template_id=10）に推奨紐付けを追加（自動有効化はせず、ユーザー選択式）
-- property_history（V61.007）と同じ apartment テンプレートに紐付け
INSERT INTO template_modules (template_id, module_id, created_at)
SELECT 10, id, NOW()
FROM module_definitions
WHERE slug = 'real_estate_disclosure';
