-- F03.10: 年間行事計画 - 前年度トレース実行ログ（監査用）
CREATE TABLE schedule_annual_copy_logs (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id               BIGINT UNSIGNED NULL,
    organization_id       BIGINT UNSIGNED NULL,
    source_academic_year  SMALLINT        NOT NULL COMMENT 'コピー元年度（例: 2025）',
    target_academic_year  SMALLINT        NOT NULL COMMENT 'コピー先年度（例: 2026）',
    total_copied          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'コピーされた行事数',
    total_skipped         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'スキップされた行事数',
    date_shift_mode       VARCHAR(20)     NOT NULL DEFAULT 'SAME_WEEKDAY' COMMENT 'EXACT_DAYS / SAME_WEEKDAY',
    executed_by           BIGINT UNSIGNED NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sacl_team (team_id, target_academic_year),
    INDEX idx_sacl_org (organization_id, target_academic_year),
    CONSTRAINT fk_sacl_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE SET NULL,
    CONSTRAINT fk_sacl_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE SET NULL,
    CONSTRAINT fk_sacl_executed_by FOREIGN KEY (executed_by) REFERENCES users (id) ON DELETE SET NULL
    -- XOR制約はアプリ層で検証（MySQL 8.0ではON DELETE SET NULLのFKカラムにCHECK制約不可）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
