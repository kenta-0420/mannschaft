-- F08.7: 試合出場メンバー登録
CREATE TABLE tournament_match_rosters (
    id             BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    match_id       BIGINT UNSIGNED  NOT NULL,
    participant_id BIGINT UNSIGNED  NOT NULL,
    user_id        BIGINT UNSIGNED  NOT NULL,
    is_starter     BOOLEAN          NOT NULL DEFAULT TRUE,
    jersey_number  SMALLINT UNSIGNED NULL,
    position       VARCHAR(30)      NULL,
    created_at     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tmr_match_user (match_id, user_id),
    INDEX idx_tmr_participant (participant_id),
    INDEX idx_tmr_user (user_id),
    CONSTRAINT fk_tmr_match FOREIGN KEY (match_id) REFERENCES tournament_matches (id) ON DELETE CASCADE,
    CONSTRAINT fk_tmr_participant FOREIGN KEY (participant_id) REFERENCES tournament_participants (id) ON DELETE CASCADE,
    CONSTRAINT fk_tmr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
