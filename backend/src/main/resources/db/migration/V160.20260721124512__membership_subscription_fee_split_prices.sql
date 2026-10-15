-- F08.9 P5 継続課金: 手数料折半が 2 サイクル目以降で効かない不具合の根治（案C・2明細サブスク）
--
-- 【何が壊れていたか】
--   手数料モデルの正典（PaymentFeeCalculator）は「総手数料 = 額面×percent_rate + flat_fee_minor を 50/50 で折半」。
--   額面 10,000・DEFAULT 5% なら 支払側請求 10,250 / application_fee 500 / 受取側着金 9,750 が正しい姿。
--
--   初回サイクルは ConnectChargeService.charge が chargeAmount(10,250) で PaymentIntent を作るため正しい。
--   ところが 2 サイクル目以降の Stripe Subscription は recurring Price を「額面のまま」(10,000) で作っており、
--   invoice.created で application_fee_amount を 500 に上書きするだけで支払側への折半上乗せ(250)を
--   invoice に加算していなかった。結果、支払側 10,000 / appFee 500 / 受取側 9,500 となり、
--   受取側が毎月「額面の 2.5%」を余分に負担していた。
--
-- 【どう直すか】
--   Subscription に 2 明細を持たせる:
--     - 会費 Price     = face_amount            （例 10,000）
--     - 手数料 Price   = FeeBreakdown.payerFee  （例 250。payerFee = 0 のときは明細を追加しない）
--   これで invoice 合計 = 10,250 となり初回サイクルと一致する。
--   application_fee_amount を 500 に上書きする既存の invoice.created 機構はそのまま維持する。
--
-- 【本移行の範囲】
--   列追加のみ（Expand）。既存契約のバックフィル（fee_model_version=1 の行を 2 へ移行する）は
--   別 PR のバッチで行うため、ここでは既存行を一切書き換えない（無損失・可逆）。

ALTER TABLE membership_subscriptions
    -- 会費分の recurring Price。案C 以前は payment_items.stripe_price_id に焼き付けていたが、
    -- 一回払い Price と金額が別物で汚染の元だったため契約側（本テーブル）で保持する。
    ADD COLUMN stripe_price_id VARCHAR(64) NULL
        COMMENT '会費分の Stripe recurring Price ID（price_xxx）' AFTER stripe_subscription_id,

    -- 支払側の折半手数料分の recurring Price。payerFee = 0 の契約では明細を作らないため NULL。
    ADD COLUMN stripe_surcharge_price_id VARCHAR(64) NULL
        COMMENT '支払側手数料分の Stripe recurring Price ID（payerFee=0 なら NULL）' AFTER stripe_price_id,

    -- 手数料モデルの世代。1 = 旧（額面のみを請求＝折半が効いていない既存契約。バックフィル対象）、
    -- 2 = 新（額面＋支払側折半を 2 明細で請求）。既存行は既定値 1 のまま据え置く。
    ADD COLUMN fee_model_version TINYINT NOT NULL DEFAULT 1
        COMMENT '手数料モデル世代（1=旧:額面のみ / 2=折半上乗せ済）' AFTER stripe_surcharge_price_id;

-- 旧モデルのまま残っている契約をバックフィルバッチが拾えるようにする（別 PR で消化する残務の索引）。
CREATE INDEX idx_membership_subscriptions_fee_model_version
    ON membership_subscriptions (fee_model_version);
