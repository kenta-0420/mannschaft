-- F06.4: 活動記録テンプレートテーブル
CREATE TABLE activity_templates (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type            VARCHAR(20) NOT NULL,
    team_id               BIGINT UNSIGNED NULL,
    organization_id       BIGINT UNSIGNED NULL,
    name                  VARCHAR(100) NOT NULL,
    description           VARCHAR(500) NULL,
    icon                  VARCHAR(50) NULL,
    color                 VARCHAR(7) NULL,
    default_title_pattern VARCHAR(200) NULL,
    default_visibility    VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY',
    default_location      VARCHAR(200) NULL,
    source_template_id    BIGINT UNSIGNED NULL,
    share_code            VARCHAR(20) NULL,
    is_shared             BOOLEAN NOT NULL DEFAULT FALSE,
    is_official           BOOLEAN NOT NULL DEFAULT FALSE,
    use_count             INT UNSIGNED NOT NULL DEFAULT 0,
    import_count          INT UNSIGNED NOT NULL DEFAULT 0,
    created_by            BIGINT UNSIGNED NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at            DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_at_team (team_id, deleted_at),
    INDEX idx_at_org (organization_id, deleted_at),
    INDEX idx_at_system (scope_type, is_official, deleted_at),
    UNIQUE KEY uq_at_share_code (share_code),
    INDEX idx_at_source (source_template_id),
    CONSTRAINT chk_at_scope CHECK (
        (scope_type = 'SYSTEM' AND team_id IS NULL AND organization_id IS NULL)
        OR (scope_type = 'TEAM' AND team_id IS NOT NULL AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_at_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_at_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_at_source FOREIGN KEY (source_template_id) REFERENCES activity_templates (id) ON DELETE SET NULL,
    CONSTRAINT fk_at_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
