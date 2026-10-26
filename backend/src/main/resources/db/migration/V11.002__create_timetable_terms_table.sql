-- F03.9: 時間割管理 - 学期・期間定義
CREATE TABLE timetable_terms (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL     COMMENT 'チーム固有の学期（NULL=組織レベル）',
    organization_id BIGINT UNSIGNED NULL     COMMENT '組織レベルの学期テンプレート（NULL=チーム固有）',
    academic_year   SMALLINT        NOT NULL COMMENT '年度（例: 2026）',
    name            VARCHAR(100)    NOT NULL COMMENT '学期名（例: 1学期, 前期, 春学期）',
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    sort_order      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '年度内の表示順',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tt_team_year (team_id, academic_year),
    INDEX idx_tt_org_year (organization_id, academic_year),
    CONSTRAINT fk_tt_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_timetable_term_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT chk_term_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT chk_term_date_order CHECK (start_date < end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
