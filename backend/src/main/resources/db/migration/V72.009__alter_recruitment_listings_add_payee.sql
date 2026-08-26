-- F22.1 市（Market）謝礼決済 P2-a: 札ごとの受領主体を表現する列を追加（非破壊）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §4.1
-- 既存行は payee_kind=NULL（決済無効札）で後方互換。payment_enabled=FALSE の既存札は CHECK を満たす。
-- payee_user_id/payee_user は users への論理参照（FKなし・クロスドメイン）。
ALTER TABLE recruitment_listings
    ADD COLUMN payee_kind    VARCHAR(8)      NULL COMMENT '受領主体 USER/TEAM/ORG（札ごと選択）',
    ADD COLUMN payee_user_id BIGINT UNSIGNED NULL COMMENT 'payee_kind=USER の受領者（論理参照）';

-- payment_enabled=TRUE のとき payee_kind と price は必須
ALTER TABLE recruitment_listings
    ADD CONSTRAINT chk_rl_payee
        CHECK (payment_enabled = FALSE
            OR (payee_kind IN ('USER','TEAM','ORG') AND price IS NOT NULL));

-- payee_user_id は payee_kind=USER のとき必須、それ以外（TEAM/ORG/NULL）では NULL
ALTER TABLE recruitment_listings
    ADD CONSTRAINT chk_rl_payee_user
        CHECK ((payee_kind = 'USER' AND payee_user_id IS NOT NULL)
            OR (payee_kind <> 'USER' AND payee_user_id IS NULL)
            OR (payee_kind IS NULL  AND payee_user_id IS NULL));

ALTER TABLE recruitment_listings
    ADD INDEX idx_rl_payee_user (payee_user_id);
