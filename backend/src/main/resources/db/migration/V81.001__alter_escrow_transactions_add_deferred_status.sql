-- F22.1 市（Market）謝礼決済 第三陣-b: 7日超 fallback（完了時即時払い）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2 / 02_api_design.md §5.1
--
-- カード与信は Stripe 仕様で約7日で失効するため、成立〜役務完了が7日を超える謝礼は成立時に与信を立てず、
-- 最終認証（役務完了）時に即時払い（destination charge・即 capture）へフォールバックする。成立時には与信せず
-- 起票する中間状態 DEFERRED（PI 未作成・完了時即時払い予定）を CHECK の許容集合へ追加する。
--
-- 既存データ非破壊: 既存行（PENDING_CONFIRMATION/AUTHORIZED/HELD/CAPTURED 等）の status 値は変更しない。
-- DEFERRED を許容集合へ追加するのみ。MySQL の CHECK は DROP→ADD で原子的に張り替える。
ALTER TABLE escrow_transactions
    DROP CHECK chk_et_status;

ALTER TABLE escrow_transactions
    ADD CONSTRAINT chk_et_status CHECK (status IN
        ('PENDING_CONFIRMATION','DEFERRED','AUTHORIZED','HELD','CAPTURED','PARTIALLY_REFUNDED','REFUNDED','CANCELLED','DISPUTED'));
