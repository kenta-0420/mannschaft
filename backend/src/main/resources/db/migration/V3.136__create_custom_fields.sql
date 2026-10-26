-- F01.2: 組織・チームカスタムフィールドテーブル
CREATE TABLE organization_custom_fields (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL COMMENT 'FK → organizations',
    label VARCHAR(100) NOT NULL COMMENT '項目ラベル',
    value TEXT NOT NULL COMMENT '項目値（最大1000文字）',
    display_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '並び順',
    is_visible BOOLEAN NOT NULL DEFAULT TRUE COMMENT '個別表示可否',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_org_custom_fields_org (organization_id, display_order),
    CONSTRAINT fk_org_custom_fields_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='組織カスタムフィールド';

CREATE TABLE team_custom_fields (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL COMMENT 'FK → teams',
    label VARCHAR(100) NOT NULL COMMENT '項目ラベル',
    value TEXT NOT NULL COMMENT '項目値（最大1000文字）',
    display_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '並び順',
    is_visible BOOLEAN NOT NULL DEFAULT TRUE COMMENT '個別表示可否',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_team_custom_fields_team (team_id, display_order),
    CONSTRAINT fk_team_custom_fields_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チームカスタムフィールド';
