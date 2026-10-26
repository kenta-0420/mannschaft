-- F06.2 メンバー紹介ページ
CREATE TABLE team_pages (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    title           VARCHAR(200)    NOT NULL,
    slug            VARCHAR(200)    NOT NULL,
    page_type       ENUM('MAIN', 'YEARLY') NOT NULL,
    year            SMALLINT UNSIGNED NULL,
    description     TEXT            NULL,
    cover_image_s3_key VARCHAR(500) NULL,
    visibility      ENUM('PUBLIC', 'MEMBERS_ONLY') NOT NULL DEFAULT 'MEMBERS_ONLY',
    status          ENUM('DRAFT', 'PUBLISHED') NOT NULL DEFAULT 'DRAFT',
    preview_token   VARCHAR(64)     NULL,
    preview_token_expires_at DATETIME NULL,
    allow_self_edit BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order      INT             NOT NULL DEFAULT 0,
    created_by      BIGINT UNSIGNED NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tp_slug_team (team_id, slug, deleted_at),
    UNIQUE KEY uq_tp_slug_org (organization_id, slug, deleted_at),
    UNIQUE KEY uq_tp_year_team (team_id, year, deleted_at),
    UNIQUE KEY uq_tp_year_org (organization_id, year, deleted_at),
    INDEX idx_tp_team_status (team_id, status, sort_order),
    INDEX idx_tp_org_status (organization_id, status, sort_order),
    CONSTRAINT chk_tp_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT chk_tp_year CHECK (
        (page_type = 'MAIN' AND year IS NULL)
        OR (page_type = 'YEARLY' AND year IS NOT NULL)
    ),
    CONSTRAINT fk_tp_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tp_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_tp_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
