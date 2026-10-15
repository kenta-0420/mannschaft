-- F22.1 市（Market）統一決済 R1: 手数料パターンの割当（source_kind＋任意 sub_key → policy_key）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.7
--
-- テナント横断の運用データで行が増えるため UUIDv7（原則6）。
-- policy_key は fee_policies への論理参照（FK は張らない＝料率改定で過去取引が壊れない不変性優先）。
-- organization_id は将来テナント別上書きの拡張点としてのみ確保（R1 では NULL のみ・解決順序にも挟まない・§3.5.3）。
CREATE TABLE fee_policy_assignments (
    id              BINARY(16)      NOT NULL COMMENT 'PK (UUIDv7)',
    source_kind     VARCHAR(12)     NOT NULL COMMENT 'RECRUITMENT/MEMBERSHIP/TOURNAMENT/JOBMATCHING/FLEAMARKET（解決キー）',
    sub_key         VARCHAR(40)     NULL     COMMENT '任意の細分キー（助っ人=recruitment_category 値 等）。NULL=source_kind 既定',
    policy_key      VARCHAR(40)     NOT NULL COMMENT '適用する fee_policies.policy_key（論理参照）',
    organization_id BIGINT UNSIGNED NULL     COMMENT '将来テナント別上書きの拡張点（R1 は NULL のみ）',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '割当の有効/無効',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL     COMMENT '論理削除',
    PRIMARY KEY (id),
    CONSTRAINT chk_fpa_source_kind CHECK (source_kind IN ('RECRUITMENT','MEMBERSHIP','TOURNAMENT','JOBMATCHING','FLEAMARKET')),
    -- (source_kind, sub_key, organization_id) ごとに 1 割当（NULL sub_key は source_kind 既定）。
    -- 論理削除を加味した重複防止はアプリ層で行う（MySQL は filtered unique 非対応）。
    UNIQUE KEY uk_fpa_target (source_kind, sub_key, organization_id),
    INDEX idx_fpa_policy (policy_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 手数料パターンの割当（source_kind＋sub_key → policy_key）';
