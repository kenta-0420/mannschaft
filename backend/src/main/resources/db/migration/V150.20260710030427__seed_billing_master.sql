-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: マスタ初期シード
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2
-- 単価・バンド割りはすべて「機構のみ定義」の想定値（実額はベータ終了時決定・README 冒頭注記2）。
-- 本 migration は CREATE 直後の初回投入のみを行う（マスタ表は新規作成ゆえ NOT EXISTS ガード不要）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- feature_catalog（6行）
-- ---------------------------------------------------------------------
INSERT INTO feature_catalog
    (feature_key, category, addon_available, addon_price_jpy, free_for_nonprofit,
     display_name_key, description_key, sort_order, enabled)
VALUES
    ('legacy.paid_plan_bundle', 'INTERNAL', FALSE, NULL, FALSE,
     'billing.features.legacy_paid_plan_bundle.name', 'billing.features.legacy_paid_plan_bundle.description', 0, TRUE),
    ('template.premium_modules', 'INTERNAL', TRUE, 300, FALSE,
     'billing.features.template_premium_modules.name', 'billing.features.template_premium_modules.description', 10, TRUE),
    ('reservation.notification_recipients_extended', 'INTERNAL', TRUE, 300, FALSE,
     'billing.features.reservation_notification_recipients_extended.name', 'billing.features.reservation_notification_recipients_extended.description', 20, TRUE),
    ('ads.hide', 'INTERNAL', TRUE, 300, FALSE,
     'billing.features.ads_hide.name', 'billing.features.ads_hide.description', 30, TRUE),
    ('monetization.paywall', 'REVENUE', TRUE, 300, FALSE,
     'billing.features.monetization_paywall.name', 'billing.features.monetization_paywall.description', 40, TRUE),
    ('monetization.membership_fee', 'REVENUE', TRUE, 300, FALSE,
     'billing.features.monetization_membership_fee.name', 'billing.features.monetization_membership_fee.description', 50, TRUE);

-- ---------------------------------------------------------------------
-- plans（FREE / BASIC / FULL・01 §2.2）
-- BASIC は構成・価格が R-3 未確定（README §8 R-3）。行は用意するが base_monthly_price_jpy=NULL。
-- ---------------------------------------------------------------------
INSERT INTO plans
    (plan_key, display_name_key, description_key, base_monthly_price_jpy, sort_order, enabled)
VALUES
    ('FREE', 'billing.plans.free.name', 'billing.plans.free.description', 0, 1, TRUE),
    ('BASIC', 'billing.plans.basic.name', 'billing.plans.basic.description', NULL, 2, TRUE),
    ('FULL', 'billing.plans.full.name', 'billing.plans.full.description', 2000, 3, TRUE);

-- ---------------------------------------------------------------------
-- plan_features（プラン→機能の展開表）
-- FREE: 既存の無料機能は登録しない（ガード対象外の明示にのみ使う）。
-- BASIC: 構成未確定ゆえ legacy.paid_plan_bundle のみ（hasPaidPlan 互換ブリッジ意味を保つ最小限）。
-- FULL: 全6キー。
-- ---------------------------------------------------------------------
INSERT INTO plan_features (plan_key, feature_key) VALUES
    ('BASIC', 'legacy.paid_plan_bundle'),
    ('FULL', 'legacy.paid_plan_bundle'),
    ('FULL', 'template.premium_modules'),
    ('FULL', 'reservation.notification_recipients_extended'),
    ('FULL', 'ads.hide'),
    ('FULL', 'monetization.paywall'),
    ('FULL', 'monetization.membership_fee');

-- ---------------------------------------------------------------------
-- plan_price_bands（TEAM×各プラン・01 §2.4。価格は全 NULL＝未定）
-- ---------------------------------------------------------------------
INSERT INTO plan_price_bands (plan_key, scope_kind, band_no, min_members, max_members, monthly_price_jpy) VALUES
    ('FREE',  'TEAM', 1, 1,   20,   NULL),
    ('FREE',  'TEAM', 2, 21,  50,   NULL),
    ('FREE',  'TEAM', 3, 51,  100,  NULL),
    ('FREE',  'TEAM', 4, 101, NULL, NULL),
    ('BASIC', 'TEAM', 1, 1,   20,   NULL),
    ('BASIC', 'TEAM', 2, 21,  50,   NULL),
    ('BASIC', 'TEAM', 3, 51,  100,  NULL),
    ('BASIC', 'TEAM', 4, 101, NULL, NULL),
    ('FULL',  'TEAM', 1, 1,   20,   NULL),
    ('FULL',  'TEAM', 2, 21,  50,   NULL),
    ('FULL',  'TEAM', 3, 51,  100,  NULL),
    ('FULL',  'TEAM', 4, 101, NULL, NULL);
