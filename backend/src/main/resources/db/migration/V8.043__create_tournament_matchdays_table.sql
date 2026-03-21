-- F08.7: 節・ラウンド
CREATE TABLE tournament_matchdays (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    division_id     BIGINT UNSIGNED  NOT NULL,
    name            VARCHAR(100)     NOT NULL,
    matchday_number SMALLINT UNSIGNED NOT NULL,
    scheduled_date  DATE             NULL,
    status          ENUM('SCHEDULED','IN_PROGRESS','COMPLETED') NOT NULL DEFAULT 'SCHEDULED',
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tm_div_num (division_id, matchday_number),
    INDEX idx_tm_date (scheduled_date, status),
    CONSTRAINT fk_tm_division FOREIGN KEY (division_id) REFERENCES tournament_divisions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
