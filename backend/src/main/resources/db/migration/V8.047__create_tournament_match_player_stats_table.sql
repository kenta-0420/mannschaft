-- F08.7: 試合ごとの個人成績
CREATE TABLE tournament_match_player_stats (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    match_id       BIGINT UNSIGNED NOT NULL,
    participant_id BIGINT UNSIGNED NOT NULL,
    user_id        BIGINT UNSIGNED NOT NULL,
    stat_key       VARCHAR(30)     NOT NULL,
    value_int      INT             NULL,
    value_decimal  DECIMAL(15,4)   NULL,
    value_time     TIME            NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tmps (match_id, user_id, stat_key),
    INDEX idx_tmps_user (user_id, stat_key),
    INDEX idx_tmps_participant (participant_id, stat_key),
    CONSTRAINT fk_tmps_match FOREIGN KEY (match_id) REFERENCES tournament_matches (id) ON DELETE CASCADE,
    CONSTRAINT fk_tmps_participant FOREIGN KEY (participant_id) REFERENCES tournament_participants (id) ON DELETE CASCADE,
    CONSTRAINT fk_tmps_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
