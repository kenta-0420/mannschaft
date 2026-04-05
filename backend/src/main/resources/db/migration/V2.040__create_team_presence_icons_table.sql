-- F01.4: プレゼンスステータスのカスタムアイコン
CREATE TABLE team_presence_icons (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id     BIGINT UNSIGNED NOT NULL,
    event_type  VARCHAR(20)     NOT NULL COMMENT 'HOME / GOING_OUT',
    icon        VARCHAR(10)     NOT NULL COMMENT 'カスタム絵文字',
    updated_by  BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tpi_team_type (team_id, event_type),
    CONSTRAINT fk_tpi_team FOREIGN KEY (team_id)    REFERENCES teams (id),
    CONSTRAINT fk_tpi_user FOREIGN KEY (updated_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
