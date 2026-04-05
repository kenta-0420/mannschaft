-- module_definitions への webhook_api モジュール追加
-- module_definitions テーブルが存在しない場合は適用をスキップ
INSERT INTO module_definitions (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at)
SELECT 'Webhook/外部API連携', 'webhook_api', '外部サービスとのWebhook連携・APIキー管理機能', 'OPTIONAL', 43, 0, 14, 1, NOW(), NOW()
WHERE EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'module_definitions'
);
