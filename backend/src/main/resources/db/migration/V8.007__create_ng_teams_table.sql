-- F08.1: NGチーム設定テーブル
CREATE TABLE ng_teams (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NOT NULL,
    blocked_team_id BIGINT UNSIGNED NOT NULL,
    reason          VARCHAR(500)    NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ng_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_ng_blocked FOREIGN KEY (blocked_team_id) REFERENCES teams(id) ON DELETE CASCADE,
    UNIQUE KEY uq_ng_pair (team_id, blocked_team_id),
    INDEX idx_ng_team (team_id),
    INDEX idx_ng_blocked (blocked_team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
