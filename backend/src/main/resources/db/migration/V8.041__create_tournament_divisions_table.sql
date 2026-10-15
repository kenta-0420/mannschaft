-- F08.7: ディビジョン（1部、2部等）
CREATE TABLE tournament_divisions (
    id                      BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    tournament_id           BIGINT UNSIGNED  NOT NULL,
    name                    VARCHAR(100)     NOT NULL,
    level                   TINYINT UNSIGNED NOT NULL DEFAULT 1,
    promotion_slots         TINYINT UNSIGNED NOT NULL DEFAULT 0,
    relegation_slots        TINYINT UNSIGNED NOT NULL DEFAULT 0,
    playoff_promotion_slots TINYINT UNSIGNED NOT NULL DEFAULT 0,
    max_participants        SMALLINT UNSIGNED NULL,
    sort_order              INT              NOT NULL DEFAULT 0,
    created_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_td_tournament (tournament_id, level, sort_order),
    CONSTRAINT fk_td_tournament FOREIGN KEY (tournament_id) REFERENCES tournaments (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
