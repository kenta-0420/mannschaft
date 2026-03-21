-- カルテセクション設定テーブル
CREATE TABLE chart_section_settings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    section_type VARCHAR(30) NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_css_team FOREIGN KEY (team_id) REFERENCES teams(id),
    CONSTRAINT uq_css_team_section UNIQUE (team_id, section_type)
);
