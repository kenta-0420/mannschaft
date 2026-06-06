-- F08.9 P5 第二波: stripe_customers に default_payment_method 列を追加（SetupIntent 基盤・off_session 再利用）
-- 設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.1（paymentMethodSetup: <SetupIntent結果>）
-- 採番: タイムスタンプ式（origin/main 最大 V74.20260605130020 より後にソートさせる・[[feedback_migration_version_collision]]）。
--   第一波の membership_subscriptions(V74.20260605130010)/payment_items 拡張(V74.20260605130020)の後続。
--
-- 用途: 継続課金（subscribe・案b）で初回単発 charge の後、Stripe Subscription を次サイクルから off_session で
--   再利用するため、SetupIntent で confirm 済みの PaymentMethod を Customer の default として焼き付ける。
--   FE で SetupIntent を confirm（カード直送・PCI SAQ-A）した payment_method_id を
--   POST /api/v1/me/payment-methods/confirm で attach＋default 設定し、本列に保持する。
-- NULL=未保存（subscribe 時に PM 未保存なら MEMBERSHIP_BILLING_020(409) で拒否し、先に SetupIntent 導線へ誘導）。
ALTER TABLE stripe_customers
    ADD COLUMN default_payment_method VARCHAR(64) NULL
        COMMENT 'off_session 既定の Stripe PaymentMethod ID (pm_xxx)。SetupIntent confirm 後に attach＋default 設定して焼付。NULL=未保存';
