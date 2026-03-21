-- F06.4: 活動記録カスタムフィールド定義テーブル
CREATE TABLE activity_custom_fields (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    scope           VARCHAR(15) NOT NULL DEFAULT 'ACTIVITY',
    field_name      VARCHAR(100) NOT NULL,
    field_type      VARCHAR(20) NOT NULL,
    options         JSON NULL,
    unit            VARCHAR(20) NULL,
    is_required     BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order      INT NOT NULL DEFAULT 0,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_acf_id_scope (id, scope),
    INDEX idx_acf_team_scope (team_id, scope, sort_order),
    INDEX idx_acf_org_scope (organization_id, scope, sort_order),
    CONSTRAINT chk_acf_scope_xor CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_acf_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_acf_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
