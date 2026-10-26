-- F08.7: セット別スコア（バレー、テニス等）
CREATE TABLE tournament_match_sets (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    match_id    BIGINT UNSIGNED  NOT NULL,
    set_number  TINYINT UNSIGNED NOT NULL,
    home_score  SMALLINT UNSIGNED NOT NULL,
    away_score  SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tms_match_set (match_id, set_number),
    CONSTRAINT fk_tms_match FOREIGN KEY (match_id) REFERENCES tournament_matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
