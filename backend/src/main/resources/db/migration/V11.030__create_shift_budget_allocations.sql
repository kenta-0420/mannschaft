-- F08.7 Phase 9-β: シフト予算割当テーブル
-- 設計書 F08.7 (v1.2) §5.2 / §5.7 に準拠。
--
-- マスター御裁可 Q1（案A）: project_id を本マイグレーションで NULLABLE 配置し、
-- UNIQUE 制約 (uq_sba_scope_category_period) を最終形（project_id を含む）で投入する。
-- これにより Phase 9-γ で V11.035 の ALTER TABLE が不要となる。
--
-- 補足: Phase 9-γ で「FK fk_sba_project」の追加は別マイグレーション（V11.035）で実施。
-- 本マイグレーションでは FK を貼らず、カラムだけ用意する（projects テーブルへの参照は
-- F02.3 で既に存在するため、Phase 9-γ で FK 制約のみを追加すれば良い）。
--
-- 修正 (MySQL 8.0): STORED 生成カラム経由の UNIQUE は FK ベースカラムに使えない
-- (Error 3192: Cannot add foreign key on the base column of stored column)。
-- MySQL 8.0.13+ の関数インデックスで同等の NULL-safe UNIQUE を実現する。

CREATE TABLE shift_budget_allocations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    team_id BIGINT UNSIGNED DEFAULT NULL,
    project_id BIGINT UNSIGNED DEFAULT NULL,
    fiscal_year_id BIGINT UNSIGNED NOT NULL,
    budget_category_id BIGINT UNSIGNED NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    allocated_amount DECIMAL(12,0) NOT NULL,
    consumed_amount DECIMAL(12,0) NOT NULL DEFAULT 0,
    confirmed_amount DECIMAL(12,0) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    note VARCHAR(500) DEFAULT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),

    -- インデックス（設計書 §5.2 準拠）
    INDEX idx_sba_org_period (organization_id, period_start, period_end),
    INDEX idx_sba_team_period (team_id, period_start, period_end),
    INDEX idx_sba_project (project_id),
    INDEX idx_sba_fiscal (fiscal_year_id),
    INDEX idx_sba_currency (currency),

    -- CHECK 制約（設計書 §5.2 準拠）
    CONSTRAINT chk_sba_amount CHECK (allocated_amount >= 0),
    CONSTRAINT chk_sba_period CHECK (period_start <= period_end),
    CONSTRAINT chk_sba_consumed CHECK (
        consumed_amount >= 0
        AND confirmed_amount >= 0
        AND confirmed_amount <= consumed_amount
    ),

    -- FK 制約
    -- organization_id: 多テナント分離。組織削除時は割当も消える
    CONSTRAINT fk_sba_organization FOREIGN KEY (organization_id)
        REFERENCES organizations(id) ON DELETE CASCADE,
    -- team_id: NULL 許容（組織全体）。チーム削除時は割当も消える
    CONSTRAINT fk_sba_team FOREIGN KEY (team_id)
        REFERENCES teams(id) ON DELETE CASCADE,
    -- fiscal_year_id: 年度を消すと履歴が壊れるので RESTRICT
    CONSTRAINT fk_sba_fiscal_year FOREIGN KEY (fiscal_year_id)
        REFERENCES budget_fiscal_years(id) ON DELETE RESTRICT,
    -- budget_category_id: 費目削除を防止
    CONSTRAINT fk_sba_budget_category FOREIGN KEY (budget_category_id)
        REFERENCES budget_categories(id) ON DELETE RESTRICT,
    -- created_by: ユーザー削除を防止（監査履歴保持）
    CONSTRAINT fk_sba_created_by FOREIGN KEY (created_by)
        REFERENCES users(id) ON DELETE RESTRICT
    -- TODO Phase 9-γ (V11.035): project_id への FK 追加
    --   CONSTRAINT fk_sba_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- UNIQUE 制約: NULL-safe で「同組織・同チーム・同費目・同期間・論理削除考慮」を保証。
-- MySQL 8.0.13+ の関数インデックスで COALESCE による番兵値を使う。
-- （STORED 生成カラムは FK ベースカラムに使えないため関数インデックスで代替）
CREATE UNIQUE INDEX uq_sba_scope_category_period
    ON shift_budget_allocations (
        organization_id,
        (COALESCE(team_id, 0)),
        (COALESCE(project_id, 0)),
        budget_category_id,
        period_start,
        period_end,
        (COALESCE(deleted_at, '9999-12-31 00:00:00'))
    );
