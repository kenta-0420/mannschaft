-- F08.9 R2-2 根治: escrow_transactions に stripe_idempotency_key を追加し、
-- 即時 charge（会費=MEMBERSHIP）の業務冪等キーを (source_kind, source_id) から idempotency_key へ移す。
-- 設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §9（冪等）
--
-- 背景（実機 2026-06-07 発見・R2-2）:
--   ConnectChargeService.charge の冪等チェックが findBySourceKindAndSourceId(MEMBERSHIP, source_id) のみで、
--   P5 継続課金（source_id=payment_item_id）と P7 協会請求（source_id=team_id）が同じ MEMBERSHIP 名前空間で
--   source_id 値が一致すると衝突し、P7 が P5 の escrow を「冪等ヒット」と誤判定して実 charge なしに流用していた。
--   呼び出し側が渡す idempotencyKey（Idempotency-Key ヘッダ起源・P5/P7 で別値・Stripe へも橋渡し済み）を
--   業務冪等キーの正とすることで、source_id 値が一致しても別取引は別 escrow になることを保証する。
--
-- 非破壊:
--   - 既存行（謝礼 RECRUITMENT 等）には idempotency_key を付けていなかったため一旦 NULL 許容で追加する。
--   - UNIQUE は張らない（理由: (a) 既存行は NULL のままで複数 NULL を許容する必要がある、(b) cancel/refund 後の
--     再 charge は同一 idempotency_key を再利用しないため不要、(c) Stripe 側 Idempotency-Key と
--     アプリ層の findByStripeIdempotencyKey 事前チェックで二重作成は既に防げる）。検索用に INDEX のみ付与する。

ALTER TABLE escrow_transactions
    ADD COLUMN stripe_idempotency_key VARCHAR(255) NULL
        COMMENT 'Idempotency-Key ヘッダ起源の業務冪等キー（即時 charge の二重起票防止・R2-2）。Stripe へも橋渡し'
        AFTER stripe_payment_intent_id;

CREATE INDEX idx_et_idempotency_key ON escrow_transactions (stripe_idempotency_key);
