CREATE TABLE member_availability_defaults (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED  NOT NULL,
    team_id         BIGINT UNSIGNED  NOT NULL,
    day_of_week     TINYINT UNSIGNED NOT NULL,
    start_time      TIME             NOT NULL,
    end_time        TIME             NOT NULL,
    preference      VARCHAR(20)      NOT NULL,
    note            VARCHAR(200),
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_mad_user_team_dow_time (user_id, team_id, day_of_week, start_time, end_time),

    CONSTRAINT fk_mad_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_mad_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メンバーデフォルト勤務可能時間';
