-- パーミッショングループテーブル（DEPUTY_ADMIN / MEMBER 向け権限束）
CREATE TABLE permission_groups (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    target_role VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_permission_groups_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_permission_groups_org FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_permission_groups_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_permission_groups_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL)
    )
);
