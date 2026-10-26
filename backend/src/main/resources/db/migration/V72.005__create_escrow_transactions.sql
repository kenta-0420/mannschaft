-- F22.1 市（Market）謝礼決済 P2-a: エスクロー取引（PaymentIntent 1:1）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2
-- payee_connect_account_id は connect_accounts.id への論理参照（FKなし＝台帳不変性優先）。
-- source_id/source_participant_id/payer_scope_id/payee_user 系はクロスドメイン論理参照（FKなし）。
CREATE TABLE escrow_transactions (
    id                        BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    source_kind               VARCHAR(12)      NOT NULL COMMENT 'RECRUITMENT/JOBMATCHING/FLEAMARKET',
    source_id                 BIGINT UNSIGNED  NOT NULL COMMENT '出所ID（論理参照）',
    source_participant_id     BIGINT UNSIGNED  NULL     COMMENT '個別応募の特定用（論理参照）',
    payer_scope_kind          VARCHAR(8)       NOT NULL COMMENT '支払者種別 USER/TEAM/ORG',
    payer_scope_id            BIGINT UNSIGNED  NOT NULL COMMENT '支払者ID（論理参照）',
    payer_stripe_customer_id  VARCHAR(32)      NULL     COMMENT '支払者の Stripe Customer（cus_xxx）',
    payee_kind                VARCHAR(8)       NOT NULL COMMENT '受領者種別 USER/TEAM/ORG',
    payee_connect_account_id  BINARY(16)       NOT NULL COMMENT 'connect_accounts.id（論理参照・FKなし）',
    organization_id           BIGINT UNSIGNED  NULL     COMMENT 'テナント絞り込み（シャードキー候補）',
    stripe_payment_intent_id  VARCHAR(32)      NULL     COMMENT 'pi_xxx（与信作成後にセット）',
    amount                    INT UNSIGNED     NOT NULL COMMENT '支払総額（JPY＝円整数・最小単位）',
    currency                  CHAR(3)          NOT NULL DEFAULT 'JPY' COMMENT 'ISO 4217',
    application_fee_amount    INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT 'プラットフォーム手数料（円整数）',
    status                    VARCHAR(20)      NOT NULL DEFAULT 'AUTHORIZED' COMMENT '取引状態（CHECK 7値）',
    authorized_at             DATETIME         NULL     COMMENT '与信成立日時（UTC）',
    captured_at               DATETIME         NULL     COMMENT 'capture（払出確定）日時（UTC）',
    cancelled_at              DATETIME         NULL     COMMENT '与信取消日時（UTC）',
    hold_expires_at           DATETIME         NULL     COMMENT 'authorization hold 失効予定（最大7日）',
    created_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_et_source_kind CHECK (source_kind IN ('RECRUITMENT','JOBMATCHING','FLEAMARKET')),
    CONSTRAINT chk_et_payee_kind  CHECK (payee_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_et_payer_kind  CHECK (payer_scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_et_status CHECK (status IN
        ('AUTHORIZED','HELD','CAPTURED','PARTIALLY_REFUNDED','REFUNDED','CANCELLED','DISPUTED')),
    CONSTRAINT chk_et_fee CHECK (application_fee_amount <= amount),
    UNIQUE KEY uk_et_pi (stripe_payment_intent_id),
    INDEX idx_et_source (source_kind, source_id),
    INDEX idx_et_payee (payee_connect_account_id),
    INDEX idx_et_org (organization_id),
    INDEX idx_et_status_hold (status, hold_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 エスクロー取引（PaymentIntent 1:1）';
