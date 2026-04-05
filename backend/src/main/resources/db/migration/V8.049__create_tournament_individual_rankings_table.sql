-- F08.7: 個人ランキングの非正規化キャッシュ
CREATE TABLE tournament_individual_rankings (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    tournament_id       BIGINT UNSIGNED  NOT NULL,
    user_id             BIGINT UNSIGNED  NOT NULL,
    participant_id      BIGINT UNSIGNED  NOT NULL,
    stat_key            VARCHAR(30)      NOT NULL,
    `rank`              SMALLINT UNSIGNED NOT NULL,
    total_value_int     INT              NULL,
    total_value_decimal DECIMAL(15,4)    NULL,
    total_value_time    TIME             NULL,
    matches_played      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    last_calculated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tir (tournament_id, stat_key, user_id),
    INDEX idx_tir_rank (tournament_id, stat_key, `rank`),
    INDEX idx_tir_user (user_id, stat_key),
    CONSTRAINT fk_tir_tournament FOREIGN KEY (tournament_id) REFERENCES tournaments (id) ON DELETE CASCADE,
    CONSTRAINT fk_tir_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_tir_participant FOREIGN KEY (participant_id) REFERENCES tournament_participants (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
