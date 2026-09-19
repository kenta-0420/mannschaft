-- CMP-011 P1: 同じ Idempotency-Key の並行再送を DB でも物理排他する。
-- NULL は手動決済等の既存行であり、MySQL の UNIQUE は複数 NULL を許容するため後方互換。
-- 再課金は新しいキーを使う契約なので、キャンセル/返金後の再課金も妨げない。
ALTER TABLE escrow_transactions
    ADD CONSTRAINT uk_et_stripe_idempotency_key UNIQUE (stripe_idempotency_key);

-- escrow と member_payment は会費起票で 1:1。既存の手動記録（NULL）は影響を受けない。
ALTER TABLE member_payments
    ADD CONSTRAINT uk_mp_escrow_transaction_id UNIQUE (escrow_transaction_id);
