CREATE TABLE shift_positions (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED  NOT NULL,
    name            VARCHAR(50)      NOT NULL,
    display_order   INT              NOT NULL DEFAULT 0,
    is_active       BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_sp_team_name (team_id, name),

    CONSTRAINT fk_sp_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフトポジション定義';
