-- F22.1 市（Market）統一決済 P2-b: escrow_transactions のスキーマ拡張（統一基盤化・非破壊）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2 / §5
--
-- 目的（3点）:
--   (1) face_amount（額面）列を追加し、amount（=額面+2.5%上乗せの実請求額）と区別する。
--   (2) capture_mode（MANUAL=エスクロー/AUTOMATIC=即時）列を追加し、謝礼と会費のモードを区別する。
--   (3) source_kind の CHECK に MEMBERSHIP（会費・即時モード・設計A）を追加する。
--
-- 既存データ非破壊:
--   - capture_mode は DEFAULT 'MANUAL'（既存=エスクロー謝礼相当）で既存行を壊さない。
--   - face_amount は一旦 NULL 許容で追加 → 既存行を amount から逆算（amount = round(face×1.025) の逆＝round(amount/1.025)）でバックフィル → NOT NULL 化。
--     P2-a 投入直後で実データは存在しないため from-scratch でも空テーブルへの安全な ALTER となる。
--   - source_kind の CHECK 差し替えは MySQL 仕様上 DROP→ADD（既存値 RECRUITMENT 等はすべて新 CHECK を満たすため非破壊）。

-- (2) capture_mode: MANUAL（エスクローモード=謝礼・与信後 capture）/ AUTOMATIC（即時モード=会費・即 capture）
ALTER TABLE escrow_transactions
    ADD COLUMN capture_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL(エスクロー=謝礼)/AUTOMATIC(即時=会費)' AFTER source_kind;

ALTER TABLE escrow_transactions
    ADD CONSTRAINT chk_et_capture_mode CHECK (capture_mode IN ('MANUAL','AUTOMATIC'));

-- (1) face_amount: 額面（受取側が設定した謝礼/会費の元値）。まず NULL 許容で追加。
ALTER TABLE escrow_transactions
    ADD COLUMN face_amount INT UNSIGNED NULL
        COMMENT '額面（受取側設定・円整数）。amount = face_amount + round(face_amount × 0.025)' AFTER stripe_payment_intent_id;

-- 既存行のバックフィル: amount は額面に 2.5% 上乗せした請求額のため、額面 ≒ round(amount / 1.025)。
-- （P2-a 直後で実データなし。from-scratch では 0 行更新となり安全。）
UPDATE escrow_transactions
   SET face_amount = ROUND(amount / 1.025)
 WHERE face_amount IS NULL;

-- バックフィル後に NOT NULL 化（設計書 §3.2 の最終形に一致）。
ALTER TABLE escrow_transactions
    MODIFY COLUMN face_amount INT UNSIGNED NOT NULL
        COMMENT '額面（受取側設定・円整数）。amount = face_amount + round(face_amount × 0.025)';

-- (3) source_kind の CHECK に MEMBERSHIP を追加（DROP→ADD で差し替え）。
ALTER TABLE escrow_transactions
    DROP CONSTRAINT chk_et_source_kind;

ALTER TABLE escrow_transactions
    ADD CONSTRAINT chk_et_source_kind
        CHECK (source_kind IN ('RECRUITMENT','MEMBERSHIP','JOBMATCHING','FLEAMARKET'));
