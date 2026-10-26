-- F06.2 プロフィール拡張フィールド定義
CREATE TABLE member_profile_fields (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    field_name      VARCHAR(100)    NOT NULL,
    field_type      ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT') NOT NULL DEFAULT 'TEXT',
    options         JSON            NULL,
    is_required     BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mpf_team (team_id, sort_order),
    INDEX idx_mpf_org (organization_id, sort_order),
    CONSTRAINT chk_mpf_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_mpf_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_mpf_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
