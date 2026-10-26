-- F08.7: ディビジョンへの参加チーム
CREATE TABLE tournament_participants (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    division_id   BIGINT UNSIGNED NOT NULL,
    team_id       BIGINT UNSIGNED NOT NULL,
    seed          SMALLINT UNSIGNED NULL,
    display_name  VARCHAR(100)    NULL,
    status        ENUM('REGISTERED','ACTIVE','WITHDRAWN','DISQUALIFIED') NOT NULL DEFAULT 'REGISTERED',
    joined_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tp_div_team (division_id, team_id),
    INDEX idx_tp_team (team_id),
    CONSTRAINT fk_tp_division FOREIGN KEY (division_id) REFERENCES tournament_divisions (id) ON DELETE CASCADE,
    CONSTRAINT fk_tourn_part_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
