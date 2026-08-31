-- =====================================================================
-- F20.1 料金・契約センター: billing foundation Expand
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/05_billing_center.md §5
--
-- 本 migration は DB Expand と権限カタログ登録だけを行う。
-- Stripe Price/Customer 等の外部 API は呼ばず、既存 billing_contracts 行も更新しない。
-- scope_id / organization_id / actor_id / created_by はクロスドメイン論理参照のため FK を張らない。
-- billing_* 同一ドメイン間だけ FK を張る。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0) V151 既存契約の ALTER 前番人
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS billing_center_v196_precheck;

CREATE PROCEDURE billing_center_v196_precheck()
BEGIN
    DECLARE invalid_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO invalid_count
      FROM billing_contracts
     WHERE (psp_customer_ref IS NOT NULL AND CHAR_LENGTH(psp_customer_ref) > 255)
        OR (psp_subscription_ref IS NOT NULL AND CHAR_LENGTH(psp_subscription_ref) > 255)
        OR status NOT IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED');

    IF invalid_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'F20.1 V196: incompatible legacy billing_contracts row';
    END IF;

    SELECT COUNT(*) INTO invalid_count
      FROM (
          SELECT psp_subscription_ref
            FROM billing_contracts
           WHERE psp_subscription_ref IS NOT NULL
           GROUP BY psp_subscription_ref
          HAVING COUNT(*) > 1
      ) duplicate_subscription_refs;

    IF invalid_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'F20.1 V196: duplicate legacy subscription reference';
    END IF;
END;

CALL billing_center_v196_precheck();
DROP PROCEDURE billing_center_v196_precheck;

-- ---------------------------------------------------------------------
-- 1) scope-owned Stripe Customer
-- ---------------------------------------------------------------------
CREATE TABLE billing_customers (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    psp_customer_ref VARCHAR(255) NULL,
    billing_email VARCHAR(254) NULL,
    billing_name VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    provision_attempts INT NOT NULL DEFAULT 0,
    last_provision_error_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bcu_scope (scope_kind, scope_id),
    UNIQUE KEY uk_bcu_psp (psp_customer_ref),
    KEY idx_bcu_org (organization_id),
    CONSTRAINT chk_bcu_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bcu_status CHECK (
        status IN ('PROVISIONING','ACTIVE','PROVISION_FAILED','MIGRATION_REQUIRED','CLOSED')
    ),
    CONSTRAINT chk_bcu_ref_by_status CHECK (
        (status = 'ACTIVE' AND psp_customer_ref IS NOT NULL)
        OR (status IN ('PROVISIONING','PROVISION_FAILED') AND psp_customer_ref IS NULL)
        OR status IN ('MIGRATION_REQUIRED','CLOSED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='scope所有Stripe Customer';

-- ---------------------------------------------------------------------
-- 2) catalog revision と人数 band の不変価格正本
-- ---------------------------------------------------------------------
CREATE TABLE billing_price_versions (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    product_kind VARCHAR(8) NOT NULL,
    product_key VARCHAR(64) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    catalog_revision VARCHAR(64) NOT NULL COMMENT '不変の運用識別子',
    revision_no BIGINT UNSIGNED NOT NULL COMMENT 'product/scopeごとの不変連番',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    provision_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_provision_error_code VARCHAR(64) NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0 COMMENT '可変操作CAS専用',
    created_by BIGINT UNSIGNED NULL,
    creation_source VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bpv_identity (id, product_kind, product_key, scope_kind),
    UNIQUE KEY uk_bpv_revision_no (product_kind, product_key, scope_kind, revision_no),
    UNIQUE KEY uk_bpv_catalog_revision (product_kind, product_key, scope_kind, catalog_revision),
    KEY idx_bpv_catalog (product_kind, product_key, scope_kind, effective_from, effective_until),
    KEY idx_bpv_org (organization_id),
    CONSTRAINT chk_bpv_kind CHECK (product_kind IN ('PLAN','ADDON')),
    CONSTRAINT chk_bpv_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bpv_status CHECK (
        status IN ('DRAFT','PROVISIONING','PROVISION_FAILED','READY','SCHEDULED','ACTIVE','RETIRED')
    ),
    CONSTRAINT chk_bpv_source CHECK (
        (creation_source = 'OPERATOR' AND created_by IS NOT NULL)
        OR creation_source = 'SYSTEM_BACKFILL'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='価格catalog revision（Money/Stripe Priceはbandだけが保持）';

CREATE TABLE billing_price_band_versions (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    product_kind VARCHAR(8) NOT NULL,
    product_key VARCHAR(64) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    band_no INT UNSIGNED NOT NULL,
    min_members INT UNSIGNED NOT NULL,
    max_members INT UNSIGNED NULL,
    price_version_id BINARY(16) NOT NULL,
    stripe_price_ref VARCHAR(255) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    input_amount BIGINT NOT NULL,
    tax_behavior VARCHAR(16) NOT NULL,
    tax_code_snapshot VARCHAR(64) NOT NULL,
    tax_master_snapshot JSON NOT NULL,
    amount_excluding_tax BIGINT NOT NULL,
    tax_amount BIGINT NOT NULL,
    tax_rate_basis_points INT NOT NULL,
    tax_name_snapshot VARCHAR(64) NOT NULL,
    is_included_in_price BOOLEAN NOT NULL,
    amount_including_tax BIGINT NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    provision_error_code VARCHAR(64) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    creation_source VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bpbv_stripe_price (stripe_price_ref),
    UNIQUE KEY uk_bpbv_revision_band (price_version_id, band_no),
    KEY idx_bpbv_select (
        product_kind, product_key, scope_kind, status,
        effective_from, effective_until, min_members, max_members
    ),
    CONSTRAINT fk_bpbv_price_identity
        FOREIGN KEY (price_version_id, product_kind, product_key, scope_kind)
        REFERENCES billing_price_versions (id, product_kind, product_key, scope_kind),
    CONSTRAINT chk_bpbv_kind CHECK (product_kind IN ('PLAN','ADDON')),
    CONSTRAINT chk_bpbv_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bpbv_currency CHECK (currency = 'JPY'),
    CONSTRAINT chk_bpbv_status CHECK (
        status IN ('DRAFT','PROVISIONING','PROVISION_FAILED','READY','SCHEDULED','ACTIVE','RETIRED')
    ),
    CONSTRAINT chk_bpbv_tax CHECK (tax_rate_basis_points BETWEEN 0 AND 10000),
    CONSTRAINT chk_bpbv_amount CHECK (
        input_amount >= 0 AND amount_excluding_tax >= 0
        AND tax_amount >= 0 AND amount_including_tax >= 0
    ),
    CONSTRAINT chk_bpbv_behavior CHECK (
        tax_behavior IN ('INCLUSIVE','EXCLUSIVE')
        AND is_included_in_price = (tax_behavior = 'INCLUSIVE')
    ),
    CONSTRAINT chk_bpbv_range CHECK (max_members IS NULL OR max_members >= min_members),
    CONSTRAINT chk_bpbv_active CHECK (
        (status IN ('DRAFT','PROVISIONING','PROVISION_FAILED') AND stripe_price_ref IS NULL)
        OR (status IN ('READY','SCHEDULED','ACTIVE','RETIRED') AND stripe_price_ref IS NOT NULL)
    ),
    CONSTRAINT chk_bpbv_sellable_positive CHECK (
        status NOT IN ('READY','SCHEDULED','ACTIVE') OR input_amount > 0
    ),
    CONSTRAINT chk_bpbv_source CHECK (
        (creation_source = 'OPERATOR' AND created_by IS NOT NULL)
        OR creation_source = 'SYSTEM_BACKFILL'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='人数band不変価格版';

-- ---------------------------------------------------------------------
-- 3) 既存契約を scope Customer / price band へ結ぶ（既存行は全てNULLのまま保持）
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    MODIFY COLUMN psp_customer_ref VARCHAR(255) NULL COMMENT 'Stripe Customer ID（履歴参照）',
    MODIFY COLUMN psp_subscription_ref VARCHAR(255) NULL COMMENT 'Stripe Subscription ID（webhook逆引き）',
    ADD COLUMN billing_customer_id BINARY(16) NULL AFTER psp_customer_ref,
    ADD COLUMN price_band_version_id BINARY(16) NULL AFTER price_jpy_snapshot,
    ADD COLUMN billing_cycle_anchor_at DATETIME(6) NULL,
    ADD COLUMN cancel_scheduled_at DATETIME(6) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD KEY idx_bc_customer (billing_customer_id),
    ADD KEY idx_bc_price_band (price_band_version_id),
    ADD CONSTRAINT fk_bc_customer
        FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    ADD CONSTRAINT fk_bc_price_band
        FOREIGN KEY (price_band_version_id) REFERENCES billing_price_band_versions (id);

-- 既存ポインタも同一billing domainの契約へFKで固定する。
ALTER TABLE active_contract_pointers
    ADD CONSTRAINT fk_acp_contract
        FOREIGN KEY (contract_id) REFERENCES billing_contracts (id);

-- ---------------------------------------------------------------------
-- 4) 見積り / preview
-- ---------------------------------------------------------------------
CREATE TABLE billing_quotes (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    actor_id BIGINT UNSIGNED NOT NULL,
    billing_customer_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    product_kind VARCHAR(8) NOT NULL,
    product_key VARCHAR(64) NOT NULL,
    price_band_version_id BINARY(16) NOT NULL,
    member_count INT UNSIGNED NULL,
    tax_snapshot JSON NOT NULL,
    amount_snapshot JSON NOT NULL,
    period_start DATETIME(6) NOT NULL,
    period_end DATETIME(6) NOT NULL,
    proration_at DATETIME(6) NOT NULL,
    contract_version BIGINT NULL,
    request_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_bq_actor_expiry (actor_id, expires_at),
    KEY idx_bq_scope (scope_kind, scope_id, expires_at),
    KEY idx_bq_org (organization_id),
    CONSTRAINT fk_bq_customer
        FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT fk_bq_price_band
        FOREIGN KEY (price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT chk_bq_kind CHECK (product_kind IN ('PLAN','ADDON')),
    CONSTRAINT chk_bq_scope CHECK (scope_kind IN ('USER','TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Checkout直前再照合する10分見積り';

CREATE TABLE billing_change_previews (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    actor_id BIGINT UNSIGNED NOT NULL,
    contract_id BINARY(16) NOT NULL,
    billing_customer_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    product_kind VARCHAR(8) NOT NULL DEFAULT 'PLAN',
    product_key VARCHAR(64) NOT NULL,
    from_price_band_version_id BINARY(16) NOT NULL,
    to_price_band_version_id BINARY(16) NOT NULL,
    member_count INT UNSIGNED NULL,
    tax_snapshot JSON NOT NULL,
    amount_snapshot JSON NOT NULL,
    period_start DATETIME(6) NOT NULL,
    period_end DATETIME(6) NOT NULL,
    proration_at DATETIME(6) NOT NULL,
    contract_version BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_bcp_actor_contract_expiry (actor_id, contract_id, expires_at),
    KEY idx_bcp_org (organization_id),
    CONSTRAINT fk_bcp_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_bcp_customer FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT fk_bcp_from
        FOREIGN KEY (from_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT fk_bcp_to
        FOREIGN KEY (to_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT chk_bcp_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bcp_kind CHECK (product_kind = 'PLAN')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='一回消費の変更preview';

-- ---------------------------------------------------------------------
-- 5) 契約操作の唯一の耐久leaseと従属Saga
-- ---------------------------------------------------------------------
CREATE TABLE billing_contract_operations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    contract_id BINARY(16) NOT NULL,
    billing_customer_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    kind VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    step VARCHAR(32) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    stripe_subscription_ref VARCHAR(255) NULL,
    stripe_schedule_ref VARCHAR(255) NULL,
    effective_at DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    actor_kind VARCHAR(8) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bco_idempotency (contract_id, idempotency_key),
    KEY idx_bco_contract_status (contract_id, status),
    KEY idx_bco_org (organization_id),
    CONSTRAINT fk_bco_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_bco_customer FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT chk_bco_kind CHECK (
        kind IN ('PLAN_CHANGE','CANCEL','RESUME','DOWNGRADE_TO_CANCEL',
                 'MIGRATION','MEMBER_REPRICE','REFUND')
    ),
    CONSTRAINT chk_bco_status CHECK (
        status IN ('CREATED','CALLING_STRIPE','APPLIED','FAILED',
                   'RECONCILIATION_REQUIRED','CANCELLED')
    ),
    CONSTRAINT chk_bco_actor CHECK (
        (actor_kind = 'USER' AND created_by IS NOT NULL)
        OR (actor_kind = 'SYSTEM' AND created_by IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='cancel/resume/change/migration等の契約操作Saga';

CREATE TABLE billing_contract_changes (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    operation_id BINARY(16) NOT NULL,
    contract_id BINARY(16) NOT NULL,
    billing_customer_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    from_plan_key VARCHAR(64) NOT NULL,
    to_plan_key VARCHAR(64) NOT NULL,
    from_price_band_version_id BINARY(16) NOT NULL,
    to_price_band_version_id BINARY(16) NOT NULL,
    from_amount_including_tax BIGINT NOT NULL,
    to_amount_including_tax BIGINT NOT NULL,
    stripe_invoice_ref VARCHAR(255) NULL,
    stripe_subscription_ref VARCHAR(255) NULL,
    pending_update_expires_at DATETIME(6) NULL,
    pending_update_target_snapshot JSON NULL,
    stripe_schedule_ref VARCHAR(255) NULL,
    effective_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bcc_operation (operation_id),
    UNIQUE KEY uk_bcc_idempotency (contract_id, idempotency_key),
    UNIQUE KEY uk_bcc_invoice (stripe_invoice_ref),
    UNIQUE KEY uk_bcc_schedule (stripe_schedule_ref),
    KEY idx_bcc_contract_status (contract_id, status, effective_at),
    KEY idx_bcc_org (organization_id),
    CONSTRAINT fk_bcc_operation FOREIGN KEY (operation_id) REFERENCES billing_contract_operations (id),
    CONSTRAINT fk_bcc_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_bcc_customer FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT fk_bcc_from_price_band
        FOREIGN KEY (from_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT fk_bcc_to_price_band
        FOREIGN KEY (to_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT chk_bcc_kind CHECK (kind IN ('UPGRADE','DOWNGRADE')),
    CONSTRAINT chk_bcc_status CHECK (
        status IN ('PENDING_PAYMENT','REQUIRES_ACTION','CREATING_SCHEDULE',
                   'SCHEDULED','APPLIED','FAILED','CANCELLED')
    ),
    CONSTRAINT chk_bcc_refs CHECK (
        (kind = 'UPGRADE' AND stripe_schedule_ref IS NULL)
        OR (kind = 'DOWNGRADE' AND status = 'CREATING_SCHEDULE' AND stripe_schedule_ref IS NULL)
        OR (kind = 'DOWNGRADE' AND status IN ('SCHEDULED','APPLIED') AND stripe_schedule_ref IS NOT NULL)
        OR (kind = 'DOWNGRADE' AND status IN ('FAILED','CANCELLED'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='PLAN変更Saga';

CREATE TABLE active_billing_contract_operation_pointers (
    contract_id BINARY(16) NOT NULL,
    operation_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (contract_id),
    UNIQUE KEY uk_abcop_operation (operation_id),
    CONSTRAINT fk_abcop_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_abcop_operation FOREIGN KEY (operation_id) REFERENCES billing_contract_operations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='契約単位の同時mutation耐久lease';

CREATE TABLE billing_membership_price_adjustments (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    operation_id BINARY(16) NOT NULL,
    contract_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    period_start DATETIME(6) NOT NULL,
    member_count_snapshot INT UNSIGNED NOT NULL,
    from_price_band_version_id BINARY(16) NOT NULL,
    to_price_band_version_id BINARY(16) NOT NULL,
    stripe_schedule_ref VARCHAR(255) NULL,
    status VARCHAR(24) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bmpa_operation (operation_id),
    UNIQUE KEY uk_bmpa_contract_period (contract_id, period_start),
    UNIQUE KEY uk_bmpa_schedule (stripe_schedule_ref),
    KEY idx_bmpa_org (organization_id),
    KEY idx_bmpa_status (status, period_start),
    CONSTRAINT fk_bmpa_operation FOREIGN KEY (operation_id) REFERENCES billing_contract_operations (id),
    CONSTRAINT fk_bmpa_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_bmpa_from_band
        FOREIGN KEY (from_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT fk_bmpa_to_band
        FOREIGN KEY (to_price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT chk_bmpa_status CHECK (
        status IN ('CREATED','SCHEDULED','APPLIED','FAILED','RECONCILIATION_REQUIRED','CANCELLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='次暦月の人数band価格変更Saga';

CREATE TABLE billing_customer_migrations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    operation_id BINARY(16) NOT NULL,
    contract_id BINARY(16) NOT NULL,
    billing_customer_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    legacy_psp_customer_ref VARCHAR(255) NOT NULL,
    legacy_psp_subscription_ref VARCHAR(255) NOT NULL,
    stripe_setup_intent_ref VARCHAR(255) NULL,
    setup_intent_expires_at DATETIME(6) NULL,
    default_payment_method_ref VARCHAR(255) NULL,
    stripe_schedule_ref VARCHAR(255) NULL,
    schedule_metadata_hash CHAR(64) NULL,
    effective_at DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    compensation_reason VARCHAR(500) NULL,
    idempotency_key CHAR(36) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bcm_operation (operation_id),
    UNIQUE KEY uk_bcm_contract (contract_id),
    UNIQUE KEY uk_bcm_setup (stripe_setup_intent_ref),
    UNIQUE KEY uk_bcm_schedule (stripe_schedule_ref),
    UNIQUE KEY uk_bcm_idempotency (contract_id, idempotency_key),
    KEY idx_bcm_status (status, effective_at),
    KEY idx_bcm_org (organization_id),
    CONSTRAINT fk_bcm_operation FOREIGN KEY (operation_id) REFERENCES billing_contract_operations (id),
    CONSTRAINT fk_bcm_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT fk_bcm_customer FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT chk_bcm_status CHECK (
        status IN ('CREATED','SETUP_INTENT_CREATED','PAYMENT_METHOD_COLLECTED','SCHEDULE_CREATED',
                   'OLD_CANCEL_SCHEDULED','COMPLETED','NEW_PAYMENT_PAST_DUE',
                   'COMPENSATING','COMPENSATED','FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='legacy Customer移行Saga';

-- ---------------------------------------------------------------------
-- 6) invoice / refund / dispute 投影
-- ---------------------------------------------------------------------
CREATE TABLE billing_invoices (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    billing_customer_id BINARY(16) NOT NULL,
    contract_id BINARY(16) NULL,
    organization_id BIGINT UNSIGNED NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    psp_invoice_ref VARCHAR(255) NOT NULL,
    psp_subscription_ref VARCHAR(255) NULL,
    billing_reason VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    subtotal_amount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    tax_amount BIGINT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL,
    issuer_name_snapshot VARCHAR(255) NOT NULL,
    billing_name_snapshot VARCHAR(255) NULL,
    billing_email_snapshot VARCHAR(254) NULL,
    billing_address_snapshot JSON NULL,
    finalized_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    voided_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_psp (psp_invoice_ref),
    KEY idx_bi_scope_period (scope_kind, scope_id, period_end),
    KEY idx_bi_customer_period (billing_customer_id, period_end),
    KEY idx_bi_org (organization_id),
    CONSTRAINT fk_bi_customer FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT fk_bi_contract FOREIGN KEY (contract_id) REFERENCES billing_contracts (id),
    CONSTRAINT chk_bi_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bi_currency CHECK (currency = 'JPY'),
    CONSTRAINT chk_bi_status CHECK (status IN ('DRAFT','OPEN','PAID','UNCOLLECTIBLE','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Stripe invoice不変投影';

CREATE TABLE billing_invoice_adjustments (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    invoice_id BINARY(16) NOT NULL,
    operation_id BINARY(16) NULL,
    organization_id BIGINT UNSIGNED NULL,
    kind VARCHAR(16) NOT NULL,
    psp_object_ref VARCHAR(255) NOT NULL,
    amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(128) NULL,
    effective_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bia_object (psp_object_ref),
    KEY idx_bia_invoice_kind (invoice_id, kind, effective_at),
    KEY idx_bia_operation (operation_id),
    KEY idx_bia_org (organization_id),
    CONSTRAINT fk_bia_invoice FOREIGN KEY (invoice_id) REFERENCES billing_invoices (id),
    CONSTRAINT fk_bia_operation FOREIGN KEY (operation_id) REFERENCES billing_contract_operations (id),
    CONSTRAINT chk_bia_kind CHECK (kind IN ('REFUND','CREDIT_NOTE','DISPUTE')),
    CONSTRAINT chk_bia_currency CHECK (currency = 'JPY'),
    CONSTRAINT chk_bia_amount CHECK (amount >= 0),
    CONSTRAINT chk_bia_status CHECK (
        status IN ('PENDING','SUCCEEDED','FAILED','OPEN','WON','LOST','CLOSED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='返金・credit note・disputeの不変複数行投影';

CREATE TABLE billing_invoice_lines (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    invoice_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    price_band_version_id BINARY(16) NULL,
    stripe_price_ref VARCHAR(255) NULL,
    psp_line_ref VARCHAR(255) NOT NULL,
    description_snapshot VARCHAR(500) NOT NULL,
    quantity DECIMAL(12,3) NOT NULL DEFAULT 1,
    amount_excluding_tax BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    tax_name_snapshot VARCHAR(64) NULL,
    tax_rate_basis_points INT NULL,
    tax_amount BIGINT NOT NULL DEFAULT 0,
    is_included_in_price BOOLEAN NOT NULL,
    amount_including_tax BIGINT NOT NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bil_line (invoice_id, psp_line_ref),
    KEY idx_bil_org (organization_id),
    KEY idx_bil_band (price_band_version_id),
    CONSTRAINT fk_bil_invoice FOREIGN KEY (invoice_id) REFERENCES billing_invoices (id),
    CONSTRAINT fk_bil_band
        FOREIGN KEY (price_band_version_id) REFERENCES billing_price_band_versions (id),
    CONSTRAINT chk_bil_quantity CHECK (quantity > 0),
    CONSTRAINT chk_bil_tax CHECK (
        tax_rate_basis_points IS NULL OR tax_rate_basis_points BETWEEN 0 AND 10000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='請求明細不変投影';

-- ---------------------------------------------------------------------
-- 7) webhook の billing 所有投影
-- ---------------------------------------------------------------------
ALTER TABLE stripe_webhook_events
    ADD COLUMN billing_contract_id BINARY(16) NULL,
    ADD COLUMN billing_customer_id BINARY(16) NULL,
    ADD COLUMN stripe_object_ref VARCHAR(255) NULL,
    ADD COLUMN payload_sha256 CHAR(64) NULL,
    ADD COLUMN failed_at DATETIME(6) NULL,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD KEY idx_swe_billing_contract (billing_contract_id),
    ADD KEY idx_swe_billing_customer (billing_customer_id),
    ADD KEY idx_swe_retry (failed_at, attempt_count),
    ADD CONSTRAINT fk_swe_billing_contract
        FOREIGN KEY (billing_contract_id) REFERENCES billing_contracts (id),
    ADD CONSTRAINT fk_swe_billing_customer
        FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id);

-- ---------------------------------------------------------------------
-- 8) consumer API idempotency / signed return state nonce
-- ---------------------------------------------------------------------
CREATE TABLE billing_api_idempotencies (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    actor_id BIGINT UNSIGNED NOT NULL,
    http_method VARCHAR(8) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_status SMALLINT NULL,
    response_json JSON NULL,
    lease_owner VARCHAR(64) NULL,
    lease_expires_at DATETIME(6) NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bai_actor_request (actor_id, http_method, request_path, idempotency_key),
    KEY idx_bai_expiry (expires_at),
    KEY idx_bai_lease (status, lease_expires_at),
    CONSTRAINT chk_bai_status CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_bai_response CHECK (
        response_status IS NULL OR response_status BETWEEN 200 AND 599
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='消費者変更APIの冪等応答';

CREATE TABLE billing_return_state_nonces (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    nonce_hash CHAR(64) NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    stripe_session_ref VARCHAR(255) NULL,
    billing_customer_id BINARY(16) NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_brsn_nonce (nonce_hash),
    KEY idx_brsn_expiry (expires_at, consumed_at),
    KEY idx_brsn_actor_scope (actor_id, scope_kind, scope_id),
    KEY idx_brsn_org (organization_id),
    CONSTRAINT fk_brsn_customer
        FOREIGN KEY (billing_customer_id) REFERENCES billing_customers (id),
    CONSTRAINT chk_brsn_purpose CHECK (
        purpose IN ('CHECKOUT_SUCCESS','CHECKOUT_CANCEL','PORTAL_RETURN','PAYMENT_ACTION_RETURN')
    ),
    CONSTRAINT chk_brsn_scope CHECK (scope_kind IN ('USER','TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='return state一回消費nonce。URL/メール等PIIは保存しない';

-- ---------------------------------------------------------------------
-- 9) 課金管理permission catalog
-- ---------------------------------------------------------------------
INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('MANAGE_TEAM_BILLING', 'チームの料金・契約を管理', 'TEAM', NOW(), NOW()),
    ('MANAGE_ORGANIZATION_BILLING', '組織の料金・契約を管理', 'ORGANIZATION', NOW(), NOW());

-- ADMINだけを既定付与する。DEPUTY_ADMIN/MEMBER/SUPPORTERには行を作らない。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
  FROM roles r
 CROSS JOIN permissions p
 WHERE r.name = 'ADMIN'
   AND p.name IN ('MANAGE_TEAM_BILLING','MANAGE_ORGANIZATION_BILLING')
   AND NOT EXISTS (
       SELECT 1
         FROM role_permissions rp
        WHERE rp.role_id = r.id
          AND rp.permission_id = p.id
   );
