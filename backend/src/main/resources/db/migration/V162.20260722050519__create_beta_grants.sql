-- =====================================================================
-- F20.3 ベータ特典: beta_grants（付与メタ）
-- =====================================================================
-- 設計書: docs/features/F20.3_beta_perks/01_data_model.md §1
-- 権利の実体は F20.1 entitlements（source_kind=BETA_GRANT・source_ref_id=beta_grants.id）。
-- 本テーブルは「誰に・いつ・どの条件で・どの機能を渡したか」の付与メタのみを保持する。
-- 主キーは UUIDv7（BINARY(16)・CLAUDE.md 原則 6）。クロスドメイン FK なし（scope_id は論理参照）。
-- =====================================================================
CREATE TABLE beta_grants (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    grant_kind VARCHAR(12) NOT NULL COMMENT 'INDIVIDUAL（個人特典）/ TEAM_ORG（チーム・組織特典）',
    beta_phase TINYINT UNSIGNED NOT NULL COMMENT 'ベータ段階（1〜4。4=1万人規模）',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG。INDIVIDUAL は USER 固定・TEAM_ORG は TEAM/ORG（CHECK）',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL',
    criteria_snapshot JSON NOT NULL COMMENT '付与時の実測値と閾値の焼き付け（例: {"activeDays":21,"requiredActiveDays":14,"membershipTenureDays":45,"requiredTenureDays":30,"evaluationWindowDays":60,"criteriaVersion":"2026-07-08T00:00:00"}）',
    active_member_count_snapshot INT UNSIGNED NULL COMMENT '付与時アクティブ人数（TEAM_ORG のみ・memberships left_at IS NULL 数。INDIVIDUAL は NULL）',
    granted_feature_keys JSON NOT NULL COMMENT '付与時に展開した feature_key 配列（plan_features(FULL) のスナップショット・例: ["ads.hide","template.premium_modules"]）',
    transferable BOOLEAN NOT NULL DEFAULT FALSE COMMENT '譲渡可否。常に FALSE（CHECK で物理固定）',
    review_flag BOOLEAN NOT NULL DEFAULT FALSE COMMENT '所有者変更等の兆候による審査待ちフラグ（true でも権利は有効のまま）',
    review_reason VARCHAR(32) NULL COMMENT 'OWNER_CHANGED / SUSPECTED_TRANSFER / MANUAL（review_flag=true のとき必須・アプリ層保証）',
    review_flagged_at DATETIME(6) NULL COMMENT 'フラグ設定日時',
    review_resolved_at DATETIME(6) NULL COMMENT '審査解決日時（問題なし）',
    review_resolved_by BIGINT UNSIGNED NULL COMMENT '審査解決者（シスアド userId・論理参照）',
    revoked_at DATETIME(6) NULL COMMENT '取消日時（終端・復活しない）',
    revoked_by BIGINT UNSIGNED NULL COMMENT '取消操作者（シスアド userId。退会等システム取消は NULL）',
    revoke_reason VARCHAR(64) NULL COMMENT '取消事由（TERMS_VIOLATION / ACCOUNT_TRANSFER / WITHDRAWAL / OTHER。revoked_at とセットで必須・アプリ層保証）',
    granted_at DATETIME(6) NOT NULL COMMENT '付与日時（チーム/組織特典の valid_until 起点）',
    granted_by BIGINT UNSIGNED NULL COMMENT '付与操作者（シスアド userId。自動付与バッチは NULL=SYSTEM）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（通常は使わない。業務上の無効化は revoked_at。基底要求の保持列）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bg_scope_phase (scope_kind, scope_id, beta_phase),
    KEY idx_bg_scope (scope_kind, scope_id),
    KEY idx_bg_review (review_flag, review_flagged_at),
    KEY idx_bg_phase (beta_phase, grant_kind),
    KEY idx_bg_org (organization_id),
    CONSTRAINT chk_bg_grant_kind CHECK (grant_kind IN ('INDIVIDUAL','TEAM_ORG')),
    CONSTRAINT chk_bg_phase CHECK (beta_phase BETWEEN 1 AND 4),
    CONSTRAINT chk_bg_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bg_kind_scope CHECK (
        (grant_kind = 'INDIVIDUAL' AND scope_kind = 'USER') OR
        (grant_kind = 'TEAM_ORG'  AND scope_kind IN ('TEAM','ORG'))
    ),
    CONSTRAINT chk_bg_not_transferable CHECK (transferable = FALSE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ベータ特典の付与メタ（権利実体は entitlements source_kind=BETA_GRANT）';
