-- F22.1 市（Market）謝礼決済 第一陣: status 意味論の根治
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2
--
-- manual-capture PaymentIntent は札主（支払者）が Stripe.js で confirm するまで真の与信（amount_capturable）が
-- 立たない。PI 作成直後に AUTHORIZED へ進めるのは意味論的に誤りで capture 失敗の温床だった。
-- 中間状態 PENDING_CONFIRMATION（PI 作成済・札主未 confirm）を新設し、真の与信確定への昇格は
-- payment_intent.amount_capturable_updated webhook 受信時のみ行う。
--
-- 既存データ非破壊: 既存行（AUTHORIZED/HELD/CAPTURED 等）の status 値は変更しない。CHECK の許容集合に
-- PENDING_CONFIRMATION を追加するのみ。MySQL の CHECK は DROP→ADD で原子的に張り替える。
ALTER TABLE escrow_transactions
    DROP CHECK chk_et_status;

ALTER TABLE escrow_transactions
    ADD CONSTRAINT chk_et_status CHECK (status IN
        ('PENDING_CONFIRMATION','AUTHORIZED','HELD','CAPTURED','PARTIALLY_REFUNDED','REFUNDED','CANCELLED','DISPUTED'));
