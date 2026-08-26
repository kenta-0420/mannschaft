-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: entitlements（中核・権利の真実源）
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.2
-- 1行 = 1スコープ × 1機能 × 1発行元の権利。判定式は同書 §3.3（isEntitled）。
-- =====================================================================
CREATE TABLE entitlements (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG（payment.connect.ScopeKind と同値）',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし・INDEX）',
    feature_key VARCHAR(64) NOT NULL COMMENT 'feature_catalog.feature_key（論理参照）',
    source_kind VARCHAR(12) NOT NULL COMMENT 'PLAN / ADDON / BETA_GRANT',
    source_ref_id BINARY(16) NOT NULL COMMENT '発行元行: PLAN/ADDON=billing_contracts.id / BETA_GRANT=beta_grants.id（論理参照）',
    valid_from DATETIME(6) NOT NULL COMMENT '有効開始（含む）',
    valid_until DATETIME(6) NULL COMMENT '有効終了（含まない・半開区間）。NULL=無期限',
    revoked_at DATETIME(6) NULL COMMENT '取消日時。NOT NULL なら期間内でも無効',
    revoked_by BIGINT UNSIGNED NULL COMMENT '取消操作者（論理参照。システム自動取消は NULL）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（通常運用では使わない。業務上の無効化は revoked_at。AbstractTenantAwareRepository 基底要求の保持列）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ent_grant (scope_kind, scope_id, feature_key, source_kind, source_ref_id, valid_from),
    KEY idx_ent_lookup (scope_kind, scope_id, feature_key, valid_until),
    KEY idx_ent_source (source_kind, source_ref_id),
    KEY idx_ent_org (organization_id),
    CONSTRAINT chk_ent_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_ent_source_kind CHECK (source_kind IN ('PLAN','ADDON','BETA_GRANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='エンタイトルメント（権利の真実源・1行=1スコープ×1機能×1発行元）';
