-- F22.1 市（Market）統一決済 R1: escrow_transactions に適用手数料パターンの焼き付け列を追加（非破壊）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2 / §3.6
--
-- 目的: charge/与信時に FeePolicyResolver で解決した policy_key を焼き付け、以後 fee_policies を改定しても
--       本取引の料率を固定する（遡及防止・README §3.4.2）。
--
-- 非破壊: DEFAULT 'DEFAULT'（率5%＋固定0＝既存挙動）で追加し既存行を壊さない。明示バックフィルで NULL を埋め、
--         NOT NULL 化する（設計書 §3.2 の最終形に一致）。P2-a 投入直後で実データは無いはず（from-scratch では 0 行更新）。

-- まず DEFAULT 値つきで追加（既存行は自動的に 'DEFAULT' が入る）。
ALTER TABLE escrow_transactions
    ADD COLUMN fee_policy_key VARCHAR(40) NOT NULL DEFAULT 'DEFAULT'
        COMMENT '適用した手数料パターンの自然キー（fee_policies.policy_key 論理参照・遡及防止の焼き付け）' AFTER application_fee_amount;

-- 念のため NULL が残っていれば DEFAULT でバックフィル（DEFAULT 値つき ADD のため通常は 0 行）。
UPDATE escrow_transactions
   SET fee_policy_key = 'DEFAULT'
 WHERE fee_policy_key IS NULL;
