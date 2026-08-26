-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: billing_contracts / active_contract_pointers
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.1 / §3.1.1
-- クロスドメインFKは張らない（scope_id・organization_id は論理参照・INDEXのみ）。
-- 新規テーブルの主キーはUUIDv7（BINARY(16)・CLAUDE.md原則6）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- billing_contracts: PLAN/ADDON 契約行（entitlements の発行元・履歴 append-only）
-- ---------------------------------------------------------------------
CREATE TABLE billing_contracts (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id 自身 / TEAM=主所属組織（無所属は NULL）/ USER=NULL',
    contract_kind VARCHAR(8) NOT NULL COMMENT 'PLAN / ADDON',
    plan_key VARCHAR(32) NULL COMMENT 'contract_kind=PLAN のとき必須（論理参照・plans）',
    feature_key VARCHAR(64) NULL COMMENT 'contract_kind=ADDON のとき必須（論理参照・feature_catalog）',
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CANCELLED / EXPIRED',
    member_count_snapshot INT UNSIGNED NULL COMMENT '契約時アクティブ人数スナップショット（TEAM/ORG のみ・memberships left_at IS NULL 数）',
    band_no_snapshot TINYINT UNSIGNED NULL COMMENT '契約時に解決した plan_price_bands.band_no（TEAM/ORG の PLAN のみ）',
    price_jpy_snapshot INT UNSIGNED NULL COMMENT '契約時単価スナップショット（円）。ベータ中=NULL（無償）。遡及防止の焼き付け（F22.1 fee_policy_key と同型）',
    contracted_at DATETIME(6) NOT NULL COMMENT '契約開始日時',
    cancelled_at DATETIME(6) NULL COMMENT '解約日時（status=CANCELLED と同時にセット）',
    created_by BIGINT UNSIGNED NULL COMMENT '契約操作者（論理参照。シスアド手動付与時はシスアドの userId）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（契約記録は原則物理削除しない）',
    PRIMARY KEY (id),
    KEY idx_bc_scope (scope_kind, scope_id, status),
    KEY idx_bc_org (organization_id),
    KEY idx_bc_plan (plan_key),
    KEY idx_bc_feature (feature_key),
    CONSTRAINT chk_bc_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bc_contract_kind CHECK (contract_kind IN ('PLAN','ADDON')),
    CONSTRAINT chk_bc_status CHECK (status IN ('ACTIVE','CANCELLED','EXPIRED')),
    CONSTRAINT chk_bc_kind_ref CHECK (
        (contract_kind = 'PLAN'  AND plan_key IS NOT NULL AND feature_key IS NULL) OR
        (contract_kind = 'ADDON' AND feature_key IS NOT NULL AND plan_key IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PLAN/ADDON 契約（entitlements の発行元・PSP 非依存）';

-- ---------------------------------------------------------------------
-- active_contract_pointers: アクティブ契約の一意性 DB 担保（H-1・§3.1.1）
--
-- 【重要・deleted_at 保持規約の意図的な例外】本表は deleted_at を持たない。
-- 解約時に uk_acp_slot スロットを解放して再契約可能にするには行を物理DELETEする
-- 必要があり、論理削除で残すとUNIQUEが効き続け再契約が誤って409で弾かれるため
-- （設計書 01 §3.1.1 の実装トラップ注記）。
-- ---------------------------------------------------------------------
CREATE TABLE active_contract_pointers (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT '論理参照',
    contract_kind VARCHAR(8) NOT NULL COMMENT 'PLAN / ADDON',
    addon_feature_key VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'ADDON のとき対象 feature_key。PLAN のとき空文字（UNIQUE を1本化するため NULL でなく '''' 固定）',
    contract_id BINARY(16) NOT NULL COMMENT '現在アクティブな billing_contracts.id（論理参照・切替時に UPDATE）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント（billing_contracts と同値・参考列。検索はスロットキーで行う）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_acp_slot (scope_kind, scope_id, contract_kind, addon_feature_key),
    KEY idx_acp_contract (contract_id),
    KEY idx_acp_org (organization_id),
    CONSTRAINT chk_acp_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_acp_contract_kind CHECK (contract_kind IN ('PLAN','ADDON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='アクティブ契約ポインタ（一意性のDB担保・履歴はbilling_contracts）';
