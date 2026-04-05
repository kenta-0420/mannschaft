-- F08.1: 募集テンプレート保存テーブル
CREATE TABLE match_request_templates (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id       BIGINT UNSIGNED NOT NULL,
    name          VARCHAR(50)     NOT NULL,
    template_json JSON            NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_mrt_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    INDEX idx_mrt_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
