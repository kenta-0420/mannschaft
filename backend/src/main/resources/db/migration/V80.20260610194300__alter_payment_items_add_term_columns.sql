-- F08.9 P6: 期別決済（TERM 型）用の有効期間列を payment_items に追加
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.2
-- TERM 型: Stripe Subscription 不要の単発 destination charge。
--   term_starts_on / term_ends_on で有効期間を指定する。
-- 既存列（type ENUM・amount・billing_interval 等）・PRIMARY KEY・FK は不変。
-- 後方互換（NULL 既定。TERM 以外の type ではこれらの列は使用しない）。
ALTER TABLE payment_items
    ADD COLUMN term_starts_on DATE NULL COMMENT '期別有効開始日（type=TERM のみ使用）' AFTER billing_interval,
    ADD COLUMN term_ends_on   DATE NULL COMMENT '期別有効終了日（type=TERM のみ使用）' AFTER term_starts_on;
