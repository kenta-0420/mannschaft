-- =============================================
-- F07.1 サービス履歴: service_record_settings テーブル
-- =============================================
CREATE TABLE service_record_settings (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id                BIGINT UNSIGNED NOT NULL,
    is_dashboard_enabled   BOOLEAN         NOT NULL DEFAULT FALSE,
    is_reaction_enabled    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_srs_team FOREIGN KEY (team_id) REFERENCES teams(id),
    UNIQUE KEY uq_srs_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
