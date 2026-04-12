-- F03.11 Phase 5b: チーム/組織ごとのペナルティ設定テーブル
CREATE TABLE recruitment_penalty_settings (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    scope_type               ENUM('TEAM', 'ORGANIZATION') NOT NULL,
    scope_id                 BIGINT      NOT NULL,
    is_enabled               BOOLEAN     NOT NULL DEFAULT FALSE COMMENT 'ペナルティ機能の有効/無効',
    threshold_count          INT         NOT NULL DEFAULT 3    COMMENT 'N回NO_SHOWでペナルティ発動',
    threshold_period_days    INT         NOT NULL DEFAULT 180  COMMENT '集計期間（日）',
    penalty_duration_days    INT         NOT NULL DEFAULT 30   COMMENT 'ペナルティ有効期間（日）',
    apply_scope              ENUM('THIS_SCOPE_ONLY', 'ALL_SCOPES') NOT NULL DEFAULT 'THIS_SCOPE_ONLY',
    auto_no_show_detection   BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '開催後自動NO_SHOW検出',
    dispute_allowed_days     INT         NOT NULL DEFAULT 14   COMMENT '異議申立可能期間（日）',
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rps_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.11 Phase5b: ペナルティ設定';
