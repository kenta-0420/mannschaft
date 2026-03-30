-- module_definitions への webhook_api モジュール追加
-- module_definitions テーブルが存在しない場合は適用をスキップ
INSERT INTO module_definitions (module_number, module_key, display_name, description, is_optional, created_at, updated_at)
SELECT 43, 'webhook_api', 'Webhook/外部API連携', '外部サービスとのWebhook連携・APIキー管理機能', 1, NOW(), NOW()
WHERE EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'module_definitions'
);
