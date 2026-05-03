-- F03.15 個人時間割: 個人時間割マスター
CREATE TABLE personal_timetables (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT UNSIGNED NOT NULL                COMMENT 'FK → users.id（所有者）',
    name                     VARCHAR(200)    NOT NULL                COMMENT '表示名（例: "2026年度 前期"）',
    academic_year            SMALLINT        NULL                    COMMENT '年度（任意・ソート用）',
    term_label               VARCHAR(50)     NULL                    COMMENT '学期ラベル（例: "前期", "夏期講習"）',
    effective_from           DATE            NOT NULL                COMMENT '適用開始日',
    effective_until          DATE            NULL                    COMMENT '適用終了日（NULL=未定）',
    status                   VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / ARCHIVED',
    visibility               VARCHAR(20)     NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE / FAMILY_SHARED',
    week_pattern_enabled     BOOLEAN         NOT NULL DEFAULT FALSE  COMMENT 'A/B週切替',
    week_pattern_base_date   DATE            NULL                    COMMENT 'A週起点（week_pattern_enabled=TRUE 時必須）',
    notes                    VARCHAR(500)    NULL                    COMMENT '本人向けメモ（時間割全体）',
    created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               DATETIME        NULL                    COMMENT '論理削除',

    PRIMARY KEY (id),
    INDEX idx_pt_user_status (user_id, status),
    INDEX idx_pt_user_effective (user_id, effective_from, effective_until),

    CONSTRAINT fk_personal_timetable_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_pt_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_pt_visibility CHECK (visibility IN ('PRIVATE', 'FAMILY_SHARED')),
    CONSTRAINT chk_pt_effective_range CHECK (effective_until IS NULL OR effective_from <= effective_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 個人時間割マスター';
