-- F08.8 Phase 1: モジュール定義シードデータ
-- 既存最大 module_number = 47（real_estate_disclosure / V61.011）。本機能は 48 を確保。
-- マンション管理組合専用機能のため有料プラン限定（requires_paid_plan=1）。30日トライアルあり。
-- apartment テンプレ以外では UI を非表示にする運用は Service 層で実装（DB 制約は最小限）。
INSERT INTO module_definitions
    (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at)
VALUES
    ('修繕長期計画ダッシュボード', 'repair_longterm_plan',
     'マンション修繕の長期計画・積立金シミュレーション・相見積もりカンバン・申し送り PDF を統合管理',
     'OPTIONAL', 48, 1, 30, 1, NOW(), NOW());

-- モジュール×レベル別利用可否（ORGANIZATION / TEAM のみ利用可・PERSONAL は対象外）
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'ORGANIZATION', 1, 'マンション管理組合本体（主用途）', NOW(), NOW()
FROM module_definitions WHERE slug = 'repair_longterm_plan';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'TEAM', 1, '棟別修繕計画・専門委員会（副次対象）', NOW(), NOW()
FROM module_definitions WHERE slug = 'repair_longterm_plan';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'PERSONAL', 0, '対象外（個人レベルでは管理組合業務なし）', NOW(), NOW()
FROM module_definitions WHERE slug = 'repair_longterm_plan';

-- apartment テンプレート（template_id=10）に紐付け（ユーザー選択式モジュール）
-- F61.007 property_history / F61.013 real_estate_disclosure と同パターン
INSERT INTO template_modules (template_id, module_id, created_at)
SELECT 10, id, NOW()
FROM module_definitions WHERE slug = 'repair_longterm_plan';
