-- ※採番はマージ直前に origin/main 最大の次へ確定（暫定 V74系）
-- F08.9 P1: 第三者代理払い許可テーブル（UUIDv7・BINARY(16) PK）
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3
-- 保護者（後見）経由の代理払いはこのテーブル不要（parental_consent_links / user_care_links 参照）。
-- 本テーブルは非後見の第三者払い専用（祖父母・スポンサー等）。
-- クロスドメインFKは追加しない（beneficiary_user_id / payer_user_id / payment_item_id はすべて論理参照）。
CREATE TABLE payment_proxy_grants (
    id                      BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    organization_id         BIGINT UNSIGNED  NULL     COMMENT 'テナント（シャードキー候補）',
    beneficiary_user_id     BIGINT UNSIGNED  NOT NULL COMMENT '受益者ユーザーID（許可を出す側）。論理参照・FKなし',
    payer_user_id           BIGINT UNSIGNED  NOT NULL COMMENT '払い手ユーザーID（許可される側）。論理参照・FKなし',
    scope                   VARCHAR(16)      NOT NULL DEFAULT 'PAYMENT' COMMENT '用途固定: PAYMENT',
    payment_item_id         BIGINT UNSIGNED  NULL     COMMENT '特定項目限定（NULL=受益者の全会費を対象とする包括grant）。論理参照・FKなし',
    max_amount              INT UNSIGNED     NULL     COMMENT '1回あたり支払い上限（円整数）。NULL=上限なし（濫用抑止用）',
    status                  VARCHAR(12)      NOT NULL DEFAULT 'PENDING' COMMENT '状態: PENDING/ACTIVE/REVOKED/EXPIRED',
    effective_from          DATETIME         NOT NULL COMMENT 'grant 有効開始日時（UTC）',
    effective_until         DATETIME         NULL     COMMENT 'grant 有効終了日時（UTC）。NULL=無期限。包括grant（payment_item_id IS NULL）はNOT NULL必須（下記CHECK参照）',
    granted_via             VARCHAR(16)      NOT NULL COMMENT '権原発行経路: INVITE_TOKEN/IN_APP',
    revoked_at              DATETIME         NULL     COMMENT 'REVOKED 遷移日時（UTC）',
    revoked_by              BIGINT UNSIGNED  NULL     COMMENT '取消操作者ユーザーID（論理参照・FKなし）',
    created_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME         NULL     COMMENT '論理削除（GDPR/退会）。テナント基底 AbstractTenantAwareRepository の deleted_at 規約に対応。業務状態(status=REVOKED/EXPIRED)とは独立',
    PRIMARY KEY (id),
    KEY idx_ppg_beneficiary (beneficiary_user_id, status),
    KEY idx_ppg_payer       (payer_user_id, status),
    UNIQUE KEY uk_ppg_active (beneficiary_user_id, payer_user_id, payment_item_id, status),
    CONSTRAINT chk_ppg_status    CHECK (status IN ('PENDING','ACTIVE','REVOKED','EXPIRED')),
    CONSTRAINT chk_ppg_granted_via CHECK (granted_via IN ('INVITE_TOKEN','IN_APP')),
    -- 包括grant（payment_item_id IS NULL）は effective_until を必須とする（濫用抑止）
    CONSTRAINT chk_ppg_blanket_expiry CHECK (payment_item_id IS NOT NULL OR effective_until IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.9 第三者代理払い許可（非後見の祖父母・スポンサー等専用）';
