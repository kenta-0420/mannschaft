-- F22.1 市（Market）謝礼決済 P2-a: 受領者の Stripe Connect Express アカウント管理
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.1
-- scope_kind + scope_id で受領主体（USER/TEAM/ORG）を抽象化。クロスドメインFKは張らない（論理参照）。
CREATE TABLE connect_accounts (
    id                  BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    scope_kind          VARCHAR(8)       NOT NULL COMMENT 'USER/TEAM/ORG（受領主体の種別）',
    scope_id            BIGINT UNSIGNED  NOT NULL COMMENT '主体ID（論理参照・FKなし）',
    organization_id     BIGINT UNSIGNED  NULL     COMMENT 'テナント絞り込み用（シャードキー候補）',
    stripe_account_id   VARCHAR(32)      NOT NULL COMMENT 'Stripe Connect アカウントID（acct_xxx）',
    onboarding_status   VARCHAR(16)      NOT NULL DEFAULT 'PENDING'
                            COMMENT 'PENDING/ONBOARDING/READY/RESTRICTED/DISABLED',
    charges_enabled     BOOLEAN          NOT NULL DEFAULT FALSE COMMENT 'Stripe charges_enabled の鏡像',
    payouts_enabled     BOOLEAN          NOT NULL DEFAULT FALSE COMMENT 'Stripe payouts_enabled の鏡像（払出可否）',
    requirements_due    JSON             NULL     COMMENT 'Stripe requirements.currently_due の鏡像（最小化）',
    country             CHAR(2)          NOT NULL DEFAULT 'JP'  COMMENT 'アカウント国（当面 JP 固定）',
    default_currency    CHAR(3)          NOT NULL DEFAULT 'JPY' COMMENT '既定通貨',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME         NULL     COMMENT '論理削除（退会/解約時の切離し）',
    PRIMARY KEY (id),
    CONSTRAINT chk_ca_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_ca_onboarding CHECK (onboarding_status IN ('PENDING','ONBOARDING','READY','RESTRICTED','DISABLED')),
    UNIQUE KEY uk_ca_stripe_account (stripe_account_id),
    -- 論理削除を含む UK。NULL 同士は重複許容＝1 アクティブ行＋複数削除済を許す（再 onboarding 用）
    UNIQUE KEY uk_ca_scope (scope_kind, scope_id, deleted_at),
    INDEX idx_ca_org (organization_id),
    INDEX idx_ca_payouts (payouts_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 受領者の Stripe Connect アカウント';
