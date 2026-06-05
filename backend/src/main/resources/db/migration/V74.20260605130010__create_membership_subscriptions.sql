-- F08.9 P5 第一波: 会員（受益者）単位の継続課金テーブル（membership_subscriptions・UUIDv7・BINARY(16) PK）
-- 設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.1 / 02_api_design.md §4
-- 採番: タイムスタンプ式（origin/main 最大 V74.20260605120020 より後にソートさせる・[[feedback_migration_version_collision]]）。
--   設計書の V74.004 連番表記は out-of-order になるため使用せず、タイムスタンプ式に統一（設計書 §5 と同期）。
-- ガワだけの team_subscriptions(V9.055) とは別物。会員（受益者）単位の継続課金を表す。
-- クロスドメインFKは追加しない（user/team/org/connect_account/payment_item はすべて論理参照・INDEX のみ）。
-- fee_policy_key は加入時に解決した手数料パターンを焼き付け（遡及防止・F22.1 fee_policies）。
-- skip_until は Stripe pause_collection(behavior=void, resumes_at) の再開予定日（今月スキップ・README §4.5）。
-- face_amount/currency は加入時の額面を固定（price-lock・値上げは新規加入のみ反映）。
CREATE TABLE membership_subscriptions (
    id                          BINARY(16)        NOT NULL COMMENT 'PK (UUIDv7)',
    organization_id             BIGINT UNSIGNED   NULL     COMMENT 'テナント（シャードキー候補）。論理参照・FKなし',
    payment_item_id             BIGINT UNSIGNED   NOT NULL COMMENT '対象会費項目。論理参照・FKなし',
    beneficiary_user_id         BIGINT UNSIGNED   NOT NULL COMMENT '受益者（会員）。論理参照・FKなし',
    payer_user_id               BIGINT UNSIGNED   NOT NULL COMMENT '払い手。論理参照・FKなし',
    payment_proxy_grant_id      BINARY(16)        NULL     COMMENT '第三者代理払いの権原 payment_proxy_grants.id。論理参照・FKなし',
    scope_kind                  VARCHAR(8)        NOT NULL COMMENT '受領主体の種別: TEAM/ORG',
    scope_id                    BIGINT UNSIGNED   NOT NULL COMMENT '受領主体 ID（team_id/org_id）。論理参照・FKなし',
    payee_connect_account_id    BINARY(16)        NOT NULL COMMENT '受領 Connect 口座 connect_accounts.id。論理参照・FKなし',
    stripe_subscription_id      VARCHAR(64)       NULL     COMMENT 'Stripe Subscription ID (sub_xxx)。退避策（自前バッチ）採用時は NULL',
    stripe_customer_id          VARCHAR(64)       NULL     COMMENT '払い手の platform Customer ID (cus_xxx)',
    billing_interval            VARCHAR(8)        NOT NULL COMMENT '課金周期: MONTHLY/YEARLY',
    billing_anchor_day          TINYINT UNSIGNED  NULL     COMMENT 'ユーザ指定決済日（1-28 等）',
    status                      VARCHAR(16)       NOT NULL DEFAULT 'PENDING' COMMENT '状態: PENDING/ACTIVE/PAST_DUE/CANCELLED/EXPIRED',
    fee_policy_key              VARCHAR(40)       NOT NULL DEFAULT 'DEFAULT' COMMENT '加入時に解決した手数料パターン（遡及防止の焼き付け・F22.1 fee_policies）',
    face_amount                 INT UNSIGNED      NOT NULL COMMENT '額面（円整数・最小通貨単位・加入時に固定＝price-lock）',
    currency                    CHAR(3)           NOT NULL DEFAULT 'JPY' COMMENT '通貨（加入時に固定）',
    current_period_start        DATE              NULL     COMMENT '現サイクル開始日',
    current_period_end          DATE              NULL     COMMENT '現サイクル終了日（= 受益者の valid_until 同期）',
    cancel_at_period_end        BOOLEAN           NOT NULL DEFAULT FALSE COMMENT '期末解約フラグ（ACTIVE 内の利用者操作・status と独立）',
    cancelled_at                DATETIME          NULL     COMMENT 'CANCELLED 遷移日時',
    skip_until                  DATE              NULL     COMMENT '今月スキップ（pause_collection resumes_at）。NULL=スキップなし（README §4.5）',
    created_at                  DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME          NULL     COMMENT '論理削除（GDPR/退会）。テナント基底 AbstractTenantAwareRepository の deleted_at 規約に対応。業務状態(status)とは独立',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ms_stripe_sub (stripe_subscription_id),
    KEY idx_ms_beneficiary (beneficiary_user_id, status),
    KEY idx_ms_payer       (payer_user_id, status),
    KEY idx_ms_item        (payment_item_id),
    KEY idx_ms_org         (organization_id),
    CONSTRAINT chk_ms_scope_kind       CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_ms_billing_interval CHECK (billing_interval IN ('MONTHLY','YEARLY')),
    CONSTRAINT chk_ms_status           CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.9 会員（受益者）単位の継続課金（membership_subscriptions）';
