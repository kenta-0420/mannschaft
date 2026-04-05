-- F08.7: 昇格・降格の確定記録
CREATE TABLE tournament_promotion_records (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tournament_id    BIGINT UNSIGNED NOT NULL,
    team_id          BIGINT UNSIGNED NOT NULL,
    from_division_id BIGINT UNSIGNED NOT NULL,
    to_division_id   BIGINT UNSIGNED NOT NULL,
    type             ENUM('PROMOTION','RELEGATION','PLAYOFF_PROMOTION','PLAYOFF_RELEGATION') NOT NULL,
    final_rank       SMALLINT UNSIGNED NOT NULL,
    reason           VARCHAR(200)    NULL,
    executed_by      BIGINT UNSIGNED NOT NULL,
    executed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tpr_tournament_team (tournament_id, team_id),
    INDEX idx_tpr_tournament (tournament_id, type),
    INDEX idx_tpr_team (team_id),
    CONSTRAINT fk_tpr_tournament FOREIGN KEY (tournament_id) REFERENCES tournaments (id) ON DELETE CASCADE,
    CONSTRAINT fk_tpr_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tpr_from_division FOREIGN KEY (from_division_id) REFERENCES tournament_divisions (id),
    CONSTRAINT fk_tpr_to_division FOREIGN KEY (to_division_id) REFERENCES tournament_divisions (id),
    CONSTRAINT fk_tpr_executed_by FOREIGN KEY (executed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
