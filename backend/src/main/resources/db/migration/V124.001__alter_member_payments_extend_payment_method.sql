-- ※採番はマージ直前に origin/main 最大の次へ再確認すること（確認時点の最大 major=V123 → V124 を採用）
-- 会費「手動入金管理の実用化」: member_payments.payment_method に CASH / BANK_TRANSFER を追加。
-- MANUAL は「その他／不明」として温存（既存データ互換のため削除しない）。STRIPE はオンライン決済。
-- 設計書: docs/features/F08.9_membership_billing_paywall
-- 既存値（STRIPE/MANUAL）はそのまま有効。NOT NULL 制約は不変。
ALTER TABLE member_payments
    MODIFY COLUMN payment_method ENUM('STRIPE', 'MANUAL', 'CASH', 'BANK_TRANSFER') NOT NULL
        COMMENT '決済手段: STRIPE=オンライン決済 / CASH=現金 / BANK_TRANSFER=銀行振込 / MANUAL=その他・不明（手動記録の既定値）';
