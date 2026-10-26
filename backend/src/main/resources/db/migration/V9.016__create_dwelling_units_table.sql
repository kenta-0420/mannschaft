-- F09.1 住民台帳: 居室テーブル
CREATE TABLE dwelling_units (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(20)         NOT NULL,
    team_id             BIGINT UNSIGNED,
    organization_id     BIGINT UNSIGNED,
    unit_number         VARCHAR(50)         NOT NULL,
    floor               SMALLINT,
    area_sqm            DECIMAL(8,2),
    layout              VARCHAR(20),
    unit_type           VARCHAR(20)         NOT NULL DEFAULT 'STANDARD',
    notes               TEXT,
    resident_count      SMALLINT            NOT NULL DEFAULT 0,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,

    PRIMARY KEY (id),
    UNIQUE KEY uq_du_team_unit (team_id, unit_number),
    UNIQUE KEY uq_du_org_unit (organization_id, unit_number),
    CONSTRAINT fk_du_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_du_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    INDEX idx_du_scope (scope_type, team_id, organization_id, unit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居室';
