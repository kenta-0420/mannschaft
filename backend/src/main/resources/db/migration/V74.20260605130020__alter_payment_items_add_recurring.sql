-- F08.9 P5 第一波: payment_items に継続課金（Stripe Subscription 管理）用の列を追加
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.2
-- 採番: タイムスタンプ式（origin/main 最大 V74.20260605120020 より後にソートさせる・[[feedback_migration_version_collision]]）。
-- 本波は「継続課金（is_recurring / billing_interval）」のみを追加する。
--   TERM（期別）・tax_*（税からくり）列は §1.2 の別スコープゆえ後続波で追加し、本波には含めない。
-- 既存列（type ENUM・amount 等）・PRIMARY KEY・FK は不変。後方互換（NULL/FALSE 既定で現挙動と完全一致）。
ALTER TABLE payment_items
    ADD COLUMN is_recurring     BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '継続課金（Stripe Subscription 管理）か。TRUE の項目は P5 で Subscription を作成する対象',
    ADD COLUMN billing_interval VARCHAR(8)  NULL                   COMMENT '課金周期: MONTHLY/YEARLY（is_recurring=TRUE 時）。MONTHLY_FEE/ANNUAL_FEE と整合',
    ADD CONSTRAINT chk_pi_billing_interval CHECK (billing_interval IS NULL OR billing_interval IN ('MONTHLY','YEARLY'));
