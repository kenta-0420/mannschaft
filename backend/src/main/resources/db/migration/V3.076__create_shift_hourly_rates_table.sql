CREATE TABLE shift_hourly_rates (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED  NOT NULL,
    team_id         BIGINT UNSIGNED  NOT NULL,
    hourly_rate     DECIMAL(10,2)    NOT NULL,
    effective_from  DATE             NOT NULL,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_shr_user_team_from (user_id, team_id, effective_from),

    CONSTRAINT fk_shr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_shr_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフト時給設定';
