-- =====================================================================
-- F20.1 料金・契約センター PR4: Checkout Session 参照列と照合キュー表
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5
--
-- 1) billing_contracts へ Checkout Session 参照列を追加する。
--    これにより「この契約は既に Checkout Session を持つ」を DB だけで判定でき、
--    UNIQUE により同一 Session の二重紐付け・二重 Session 作成を物理的に拒否する。
--    psp_subscription_ref は webhook の Subscription 逆引き専用（F08.9 会費との分離キー）
--    であり流用しない。別列として持つ。
-- 2) 「Stripe 側に Checkout Session が実在するのに DB 側が倒れた」事実の受け皿を新設する。
--    従来は ERROR ログのみで機械的に回収対象を数えられなかった（金銭が絡む穴）。
--
-- scope_id / organization_id / actor_id 等のクロスドメイン論理参照には FK を張らない。
-- 照合キュー表は Stripe の不透明 ID と退避識別子しか持たないため FK は不要（索引のみ）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 契約 -> Checkout Session 参照（既存行は NULL のまま）
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    ADD COLUMN stripe_checkout_session_ref VARCHAR(255) NULL
        COMMENT 'Stripe Checkout Session ID（cs_xxx・二重Session防止の正本）'
        AFTER psp_subscription_ref,
    ADD UNIQUE KEY uk_bc_checkout_session (stripe_checkout_session_ref);

-- ---------------------------------------------------------------------
-- 2) Checkout 照合キュー（Stripe 成功後の DB 失敗の耐久記録）
-- ---------------------------------------------------------------------
CREATE TABLE billing_checkout_reconciliations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    stripe_session_ref VARCHAR(255) NOT NULL COMMENT 'Stripe Checkout Session ID（cs_xxx）',
    stripe_customer_ref VARCHAR(255) NOT NULL COMMENT 'Stripe Customer ID（cus_xxx）',
    idempotency_id BINARY(16) NOT NULL COMMENT '退避の識別子（呼び出し元が採番）',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bcr_session (stripe_session_ref),
    KEY idx_bcr_pending (status, created_at),
    KEY idx_bcr_customer (stripe_customer_ref),
    CONSTRAINT chk_bcr_status CHECK (status IN ('PENDING','RESOLVED','FAILED')),
    CONSTRAINT chk_bcr_attempt CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Checkout照合キュー。Stripe不透明IDと退避識別子のみ保持しPII/tokenは持たない';
