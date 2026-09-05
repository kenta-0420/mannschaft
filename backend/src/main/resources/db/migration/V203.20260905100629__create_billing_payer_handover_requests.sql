-- =====================================================================
-- 柱③-B 組織契約の請求担当と個人支払手段の分離（CMP-260901-1538・PR-1: DDL＋読み取り専用の土台）
-- =====================================================================
-- 設計書: docs/architecture/billing_payer_handover_design.md §4.2
-- クロスドメインFKは張らない（old_payer_user_id / new_payer_user_id は users.id への論理参照）。
-- 新規テーブルの主キーはUUIDv7（BINARY(16)・CLAUDE.md原則6）。
-- =====================================================================
CREATE TABLE billing_payer_handover_requests (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    old_contract_id BINARY(16) NOT NULL COMMENT '引継元 billing_contracts.id',
    new_contract_id BINARY(16) NULL COMMENT '引継先 billing_contracts.id（ACCEPTED 以降で確定・PENDING_HANDOVER 状態で作成）',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'TEAM または ORG のみ許容（USER は引継対象外・アプリ層で拒否）',
    scope_id BIGINT UNSIGNED NOT NULL,
    old_payer_user_id BIGINT UNSIGNED NOT NULL COMMENT '退会予定・引継元の payer',
    new_payer_user_id BIGINT UNSIGNED NULL COMMENT '承諾した引継先 ADMIN（ACCEPTED 以降で確定）',
    status VARCHAR(24) NOT NULL COMMENT 'REQUESTED/ACCEPTED/REQUIRES_PAYMENT_METHOD/SWITCHING/PARTIALLY_COMPLETED/MANUAL_INTERVENTION/COMPLETED/FAILED/EXPIRED（9値）',
    -- 生成列: 終端状態（COMPLETED/FAILED/EXPIRED）以外のときだけ old_contract_id を値として持つ。
    -- PARTIALLY_COMPLETED は「非終端・リトライ対象」、MANUAL_INTERVENTIONは「非終端・運用者のRESUME待ち」と
    -- それぞれ再定義したため、いずれもCASE式の対象外（＝値を保持し続ける）のままで正しい（設計書§3.5・§3.6.2）。
    -- 終端状態では NULL になるため UNIQUE 制約に抵触せず、同一契約への再要求（前回終了後）を許可する。
    open_old_contract_id BINARY(16) GENERATED ALWAYS AS (
        CASE WHEN status IN ('COMPLETED', 'FAILED', 'EXPIRED') THEN NULL ELSE old_contract_id END
    ) STORED COMMENT '村の現役所属重複防止と同型: 進行中(非終端)の要求のみ値を持つ生成列',
    requested_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL COMMENT '既定 requested_at + 14日。期限内未引継は期末解約へフォールバック（設計書§5.4）',
    accepted_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    psp_new_subscription_ref VARCHAR(64) NULL COMMENT 'P0-2: 新サブスク作成成功時点で永続化する一次防衛の要',
    old_cancel_scheduled_at DATETIME(6) NULL COMMENT 'R4-P1-2: 旧サブスクへの cancel_at_period_end=true 設定 API が成功した時点で永続化。NULLのままACCEPTED以降に残る行は設定が未完了/未確認であることを示し、夜次照合バッチの検出対象になる',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bphr_open_old_contract (open_old_contract_id) COMMENT '生成列+UNIQUE。同一契約に対する進行中の引継要求は同時に1件のみ（村の現役所属重複防止と同型構図）',
    KEY idx_bphr_scope (scope_kind, scope_id, status),
    KEY idx_bphr_expires (status, expires_at),
    KEY idx_bphr_new_contract (new_contract_id),
    CONSTRAINT chk_bphr_scope_kind CHECK (scope_kind IN ('TEAM','ORG')),
    CONSTRAINT chk_bphr_status CHECK (status IN (
        'REQUESTED','ACCEPTED','REQUIRES_PAYMENT_METHOD','SWITCHING',
        'PARTIALLY_COMPLETED','MANUAL_INTERVENTION','COMPLETED','FAILED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='billing_contracts の payer（請求担当）引継要求。UuidV7Entity 継承・自ドメイン内完結（クロスドメイン FK 無し）';
