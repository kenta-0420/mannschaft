-- F08.8 Phase 1: 修繕周期マスタテーブル（国交省R5 ガイドライン準拠）
-- SYSTEM seed → ORG → TEAM の3層オーバーライドを単一テーブルで表現。
-- CLAUDE.md 原則6 に従い UUIDv7 (BINARY(16)) を主キーとして採用。
CREATE TABLE repair_plan_templates (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NULL, -- SYSTEM行はNULL、ORG/TEAM行はテナントID（テナント絞り込み高速化）
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NULL,        -- SYSTEMはNULL、それ以外はorgs.id/teams.id
    category VARCHAR(60) NOT NULL,
    cycle_years SMALLINT UNSIGNED NOT NULL,
    unit_cost_per_dwelling INT UNSIGNED NOT NULL,
    source_reference VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_rpt_scope_type CHECK (scope_type IN ('SYSTEM','ORGANIZATION','TEAM')),
    CONSTRAINT chk_rpt_cycle_years CHECK (cycle_years BETWEEN 1 AND 60),
    CONSTRAINT chk_rpt_unit_cost CHECK (unit_cost_per_dwelling >= 0),
    CONSTRAINT chk_rpt_scope_consistency CHECK (
        (scope_type = 'SYSTEM' AND scope_id IS NULL)
        OR (scope_type IN ('ORGANIZATION','TEAM') AND scope_id IS NOT NULL)
    )
);

CREATE INDEX idx_rpt_scope ON repair_plan_templates (scope_type, scope_id, category, deleted_at);
CREATE INDEX idx_rpt_organization_id ON repair_plan_templates (organization_id);
-- 同一 scope の同一 category 重複は論理削除を含めて UNIQUE 制約をかけたいが、
-- MySQL は NULL を許容するため、deleted_at IS NULL の行のみ UNIQUE になるよう
-- アプリ層で重複チェック（Service 層で findByScopeAndCategoryAndDeletedAtIsNull を実施）。
