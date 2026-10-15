-- F01.2: チーム役員テーブル
CREATE TABLE team_officers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL COMMENT 'FK → teams',
    name VARCHAR(100) NOT NULL COMMENT '役員名',
    title VARCHAR(100) NOT NULL COMMENT '役職名',
    display_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '並び順',
    is_visible BOOLEAN NOT NULL DEFAULT TRUE COMMENT '個別表示可否',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_team_officers_team (team_id, display_order),
    CONSTRAINT fk_team_officers_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チーム役員一覧';
