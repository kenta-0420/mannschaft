-- 採番: 当初 V124 → #1798(reflection) が V124.001 先取り → V125.001 へ。
--       さらにマージ直前の main 取込で #1806 が V125.001 を先取りしていたため
--       V126.001 にリネーム（feedback_migration_version_collision / グローバル最大の次major）。
-- 会費「手動入金管理の実用化」: member_payments.payment_method に CASH / BANK_TRANSFER を追加。
-- MANUAL は「その他／不明」として温存（既存データ互換のため削除しない）。STRIPE はオンライン決済。
-- 設計書: docs/features/F08.9_membership_billing_paywall
-- 既存値（STRIPE/MANUAL）はそのまま有効。NOT NULL 制約は不変。
ALTER TABLE member_payments
    MODIFY COLUMN payment_method ENUM('STRIPE', 'MANUAL', 'CASH', 'BANK_TRANSFER') NOT NULL
        COMMENT '決済手段: STRIPE=オンライン決済 / CASH=現金 / BANK_TRANSFER=銀行振込 / MANUAL=その他・不明（手動記録の既定値）';
