-- Gate 基盤工事②: route ガード対象機能の feature_flags 行を seed する。
--
-- 背景（この migration が無いと何が壊れるか）:
--   FE の route ガード（frontend/app/middleware/feature-gate.global.ts）は
--   featureFlags store の isEnabled(flagKey) を見て弾く。isEnabled は
--   `flags.value[flagKey] ?? false` なので、**feature_flags に行が無いキーは恒久 false** になる。
--   さらに管理コンソールの PUT /api/v1/system-admin/feature-flags/{flagKey} は
--   FeatureFlagService.updateFlag の findByFlagKey().orElseThrow() で 404 になるため、
--   行が無いままだと「全ユーザーで必ず弾かれる」かつ「ON にする手段が無い」状態に陥る。
--   よって route ガードに束縛する gate_key は必ずここで行を作る。
--
-- 命名: 既存規約 FEATURE_{NAME}_ENABLED に合わせる
--   （V13.012 の FEATURE_EQUIPMENT_RANKING_ENABLED、
--     V144.20260707124158 の FEATURE_V9_ENABLED と同じ形）。
--   gate_key（docs/inventory/feature-inventory.yaml の release.gate_key）と
--   flag_key は**同一文字列**にしてある。対応表を挟むと綴り違いの余地が増えるため。
--   一致は番人 FeatureGateRouteMapGuardTest が CI で機械的に検証する。
--
-- 列は feature_flags の実 DDL（V10.003）に合わせて is_enabled。
-- ON DUPLICATE KEY UPDATE flag_key = flag_key で既存行（環境ごとの手動設定）は上書きしない。
--
-- ▼▼▼ 既定値 = TRUE（有効）である理由（マスター裁可 2026-08-20）▼▼▼
--   「まず今まで通り入れる状態で門番を設置し、β公開直前に管理画面から閉じる」運用とする。
--   既定 FALSE にすると、本 migration がマージされた瞬間に開発中の画面と既存 E2E が
--   一斉に入れなくなるため。したがって本 PR は「隔離の仕組みを敷く」ところまでで、
--   実際に閉じるのは β公開直前の運用操作（管理コンソールから is_enabled を FALSE に）である。
--
--   ⚠️ 唯一のリスクは「閉め忘れ」である。閉栓が必要なことは
--   docs/inventory/feature-inventory.yaml の各対象行の blockers に
--   「β公開前に FEATURE_*_ENABLED を無効化する」として記録してある。
-- ▲▲▲

INSERT INTO feature_flags (flag_key, is_enabled, description, created_at, updated_at)
VALUES
    ('FEATURE_SHIFT_ENABLED',               TRUE, 'シフト・シフト予算（route ガード対象 / feature_key=shift）', NOW(), NOW()),
    ('FEATURE_MATCHING_ENABLED',            TRUE, 'マッチング・求人（route ガード対象 / feature_key=matching）', NOW(), NOW()),
    ('FEATURE_BILLING_PAYMENT_ENABLED',     TRUE, '決済・課金・会費・ウォレット（route ガード対象 / feature_key=billing-payment）', NOW(), NOW()),
    ('FEATURE_PROMOTION_ENABLED',           TRUE, '広告・販促・サイネージ（route ガード対象 / feature_key=promotion）', NOW(), NOW()),
    ('FEATURE_MARKET_ENABLED',              TRUE, 'マーケットプレイス（route ガード対象 / feature_key=market）', NOW(), NOW()),
    ('FEATURE_WORKFLOW_FORMS_ENABLED',      TRUE, 'ワークフロー・フォーム（route ガード対象 / feature_key=workflow-forms）', NOW(), NOW()),
    ('FEATURE_FACILITY_ENABLED',            TRUE, '備品・施設・会場・駐車場（route ガード対象 / feature_key=facility）', NOW(), NOW()),
    ('FEATURE_PROPERTY_REPAIRPLAN_ENABLED', TRUE, '不動産・修繕計画（route ガード対象 / feature_key=property-repairplan）', NOW(), NOW()),
    ('FEATURE_FAMILY_CARE_ENABLED',         TRUE, '学校・家族・見守り（route ガード対象 / feature_key=family-care）', NOW(), NOW()),
    ('FEATURE_SKILL_RESUME_ENABLED',        TRUE, 'スキル・履歴書（route ガード対象 / feature_key=skill-resume）', NOW(), NOW()),
    ('FEATURE_RECRUITMENT_ENABLED',         TRUE, '募集（route ガード対象 / feature_key=recruitment）', NOW(), NOW()),
    ('FEATURE_SUCCESSION_PROXY_ENABLED',    TRUE, '事業承継・代理投票（route ガード対象 / feature_key=succession-proxy）', NOW(), NOW()),
    ('FEATURE_GDPR_DISCLOSURE_ENABLED',     TRUE, 'GDPR・情報開示（route ガード対象 / feature_key=gdpr-disclosure）', NOW(), NOW()),
    ('FEATURE_MODERATION_INCIDENT_ENABLED', TRUE, 'モデレーション・インシデント（route ガード対象 / feature_key=moderation-incident）', NOW(), NOW()),
    ('FEATURE_WEBHOOK_SYNC_ENABLED',        TRUE, 'Webhook・同期・LINE連携（route ガード対象 / feature_key=webhook-sync）', NOW(), NOW()),
    ('FEATURE_TRANSLATION_SEARCH_ENABLED',  TRUE, '翻訳・分析（route ガード対象 / feature_key=translation-search）', NOW(), NOW()),
    ('FEATURE_GAMIFICATION_ENABLED',        TRUE, 'ゲーミフィケーション・サポーター（route ガード対象 / feature_key=gamification）', NOW(), NOW())
ON DUPLICATE KEY UPDATE flag_key = flag_key;
