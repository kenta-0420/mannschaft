-- F06.2 写真アルバム
CREATE TABLE photo_albums (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id             BIGINT UNSIGNED NULL,
    organization_id     BIGINT UNSIGNED NULL,
    title               VARCHAR(200)    NOT NULL,
    description         VARCHAR(500)    NULL,
    cover_photo_id      BIGINT UNSIGNED NULL,
    event_date          DATE            NULL,
    visibility          ENUM('ALL_MEMBERS', 'SUPPORTERS_AND_ABOVE', 'ADMIN_ONLY') NOT NULL DEFAULT 'ALL_MEMBERS',
    allow_member_upload BOOLEAN         NOT NULL DEFAULT FALSE,
    allow_download      BOOLEAN         NOT NULL DEFAULT TRUE,
    photo_count         INT UNSIGNED    NOT NULL DEFAULT 0,
    created_by          BIGINT UNSIGNED NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME        NULL,
    PRIMARY KEY (id),
    INDEX idx_pa_team_date (team_id, event_date DESC),
    INDEX idx_pa_org_date (organization_id, event_date DESC),
    INDEX idx_pa_created_by (created_by),
    CONSTRAINT chk_pa_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_pa_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_pa_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_pa_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
