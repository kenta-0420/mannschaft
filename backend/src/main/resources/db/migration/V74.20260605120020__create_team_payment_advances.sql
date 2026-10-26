-- F08.9 P7 第一波: 協会請求の立替/精算記録テーブル（team_payment_advances・案3・UUIDv7・BINARY(16) PK）
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.5 / 02_api_design.md §7 / README §6.3
-- 採番: タイムスタンプ式（V74.20260605120010 の直後にソート）。
-- 協会→チーム請求を「チーム ADMIN 個人の Stripe Customer で立替課金」（案3）した事実と、後にチームから精算された事実を記録。
-- クロスドメインFKは追加しない（team/user/escrow/payment_request はすべて論理参照・INDEX のみ）。
CREATE TABLE team_payment_advances (
    id                      BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    organization_id         BIGINT UNSIGNED  NULL     COMMENT 'テナント（シャードキー候補）。論理参照・FKなし',
    team_id                 BIGINT UNSIGNED  NOT NULL COMMENT '立替の主体チーム。論理参照・FKなし',
    payer_user_id           BIGINT UNSIGNED  NOT NULL COMMENT '立替えた ADMIN 個人。論理参照・FKなし',
    escrow_transaction_id   BINARY(16)       NULL     COMMENT 'F22.1 money rail への連結。論理参照・FKなし',
    payment_request_id      BINARY(16)       NULL     COMMENT '対象の協会請求。論理参照・FKなし',
    advanced_amount         INT UNSIGNED     NOT NULL COMMENT '立替額（円整数・払い手が課金された請求額）',
    currency                CHAR(3)          NOT NULL DEFAULT 'JPY' COMMENT '通貨',
    advanced_at             DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '立替（協会請求支払い）日時',
    settlement_status       VARCHAR(12)      NOT NULL DEFAULT 'PENDING' COMMENT 'チームからの精算状態: PENDING/SETTLED',
    settled_at              DATETIME         NULL     COMMENT '精算完了日時',
    settled_confirmed_by    BIGINT UNSIGNED  NULL     COMMENT '精算を確認した者（チーム ADMIN・F04.9 確認）。論理参照・FKなし',
    created_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME         NULL     COMMENT '論理削除（GDPR/退会）。テナント基底 AbstractTenantAwareRepository の deleted_at 規約に対応。業務状態(settlement_status)とは独立',
    PRIMARY KEY (id),
    KEY idx_tpa_team    (team_id, settlement_status),
    KEY idx_tpa_payer   (payer_user_id),
    KEY idx_tpa_org     (organization_id),
    KEY idx_tpa_request (payment_request_id),
    -- 1請求＝1立替の冪等（重複起票防止）。payment_request_id は NULL を許容するが、NULL 同士は UNIQUE の対象外（MySQL）。
    UNIQUE KEY uk_tpa_request (payment_request_id),
    CONSTRAINT chk_tpa_settlement CHECK (settlement_status IN ('PENDING','SETTLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.9 協会請求の立替/精算記録（案3・team_payment_advances）';
