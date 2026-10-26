-- F08.9 P7 第一波: 協会→加盟チーム請求テーブル（payment_requests・UUIDv7・BINARY(16) PK）
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.2 / 02_api_design.md §7
-- 採番: タイムスタンプ式（origin/main 最大 V74.20260605000020 より後にソートさせる・[[feedback_migration_version_collision]]）。
-- 協会(ORG)が加盟チーム(TEAM)へ発行する請求書。escrow は payer_scope_kind=TEAM/payee_kind=ORG（V72.005 の CHECK が許可）。
-- クロスドメインFKは追加しない（issuer/payer/payee/created_by/notification はすべて論理参照・INDEX のみ）。
CREATE TABLE payment_requests (
    id                          BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    organization_id             BIGINT UNSIGNED  NOT NULL COMMENT 'テナント（請求元の協会）。論理参照・FKなし',
    issuer_scope_kind           VARCHAR(8)       NOT NULL COMMENT '請求元 scope 種別: ORG（将来 TEAM 内請求も）',
    issuer_scope_id             BIGINT UNSIGNED  NOT NULL COMMENT '請求元 ID（協会）。論理参照・FKなし',
    payer_scope_kind            VARCHAR(8)       NOT NULL COMMENT '請求先 scope 種別: TEAM',
    payer_scope_id              BIGINT UNSIGNED  NOT NULL COMMENT '請求先チーム ID。論理参照・FKなし',
    payee_connect_account_id    BINARY(16)       NOT NULL COMMENT '着金先（協会の Connect 口座）。論理参照・FKなし',
    title                       VARCHAR(120)     NOT NULL COMMENT '請求タイトル',
    description                 VARCHAR(1000)    NULL     COMMENT '請求の説明',
    face_amount                 INT UNSIGNED     NOT NULL COMMENT '額面（円整数・最小通貨単位）',
    currency                    CHAR(3)          NOT NULL DEFAULT 'JPY' COMMENT '通貨',
    tax_category                VARCHAR(16)      NULL     COMMENT '税からくり（NULL=税なし扱い・NoOpTaxPolicy）',
    due_date                    DATE             NOT NULL COMMENT '支払期限',
    status                      VARCHAR(12)      NOT NULL DEFAULT 'DRAFT' COMMENT '状態: DRAFT/SENT/VIEWED/PAID/OVERDUE/CANCELLED',
    escrow_transaction_id       BINARY(16)       NULL     COMMENT '支払い時に money rail へ連結（F22.1）。論理参照・FKなし',
    confirmable_notification_id BIGINT UNSIGNED  NULL     COMMENT '配信した確認必須通知（F04.9）。論理参照・FKなし',
    superseded_by_id            BINARY(16)       NULL     COMMENT 'CANCELLED 後の再請求で新請求を指す（再発行の追跡・自己参照）。論理参照・FKなし',
    sent_at                     DATETIME         NULL     COMMENT 'SENT 遷移日時（配信日時）',
    viewed_at                   DATETIME         NULL     COMMENT 'VIEWED 遷移日時（チーム閲覧日時）',
    paid_at                     DATETIME         NULL     COMMENT 'PAID 遷移日時（支払い日時）',
    created_by                  BIGINT UNSIGNED  NULL     COMMENT '発行者ユーザーID。論理参照・FKなし',
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME         NULL     COMMENT '論理削除（GDPR/退会）。テナント基底 AbstractTenantAwareRepository の deleted_at 規約に対応。業務状態(status)とは独立',
    PRIMARY KEY (id),
    KEY idx_pr_payer  (payer_scope_kind, payer_scope_id, status),
    KEY idx_pr_issuer (issuer_scope_kind, issuer_scope_id),
    KEY idx_pr_org    (organization_id),
    KEY idx_pr_due    (status, due_date),
    CONSTRAINT chk_pr_issuer_kind CHECK (issuer_scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_pr_payer_kind  CHECK (payer_scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_pr_status      CHECK (status IN ('DRAFT','SENT','VIEWED','PAID','OVERDUE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.9 協会→加盟チーム請求（payment_requests）';
