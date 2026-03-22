-- F03.10: 年間行事計画 - 行事カテゴリマスター（色分け用）
CREATE TABLE schedule_event_categories (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id              BIGINT UNSIGNED NULL     COMMENT 'チーム固有カテゴリ（NULL=組織レベル）',
    organization_id      BIGINT UNSIGNED NULL     COMMENT '組織レベルカテゴリ（NULL=チーム固有）',
    name                 VARCHAR(100)    NOT NULL COMMENT 'カテゴリ名（例: 式典, テスト・試験）',
    color                VARCHAR(7)      NOT NULL DEFAULT '#3B82F6' COMMENT '表示色（HEXカラーコード）',
    icon                 VARCHAR(50)     NULL     COMMENT 'アイコン識別子（フロントエンド用）',
    is_day_off_category  BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '休業日系カテゴリフラグ（TRUE=時間割DAY_OFF自動連携）',
    sort_order           TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '表示順',
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sec_team (team_id),
    INDEX idx_sec_org (organization_id),
    CONSTRAINT fk_sec_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_sec_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT chk_sec_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
