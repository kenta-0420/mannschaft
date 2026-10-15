-- ※採番はマージ直前に origin/main 最大の次へ確定（暫定 V74系）
-- F08.9 P1: member_payments に払い手分離・money rail 連結列を追加
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.1
-- クロスドメインFKは追加しない（payer_user_id等はすべて論理参照）。
-- 既存の user_id FK（受益者）・PRIMARY KEY・他の列は不変。
ALTER TABLE member_payments
    ADD COLUMN payer_user_id             BIGINT UNSIGNED  NULL     COMMENT '払い手ユーザーID（受益者と別人の場合に設定。論理参照・FKなし）',
    ADD COLUMN payment_proxy_grant_id    BINARY(16)       NULL     COMMENT '代理払い権原 payment_proxy_grants.id（論理参照・FKなし）',
    ADD COLUMN payer_relationship        VARCHAR(16)      NULL     COMMENT '払い手と受益者の関係スナップショット: SELF/GUARDIAN/GUARDIAN_PROXY/PROXY_GRANT/ADMIN_MANUAL',
    ADD COLUMN escrow_transaction_id     BINARY(16)       NULL     COMMENT 'F22.1 money rail 連結 escrow_transactions.id（論理参照・FKなし）。手動記録はNULL',
    ADD COLUMN membership_subscription_id BINARY(16)     NULL     COMMENT '継続課金親サブスク membership_subscriptions.id（論理参照・FKなし）',
    ADD INDEX idx_mp_payer   (payer_user_id, status),
    ADD INDEX idx_mp_escrow  (escrow_transaction_id),
    ADD INDEX idx_mp_subscription (membership_subscription_id);
