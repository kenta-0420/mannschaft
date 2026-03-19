-- チーム−組織所属関連テーブル
CREATE TABLE team_org_memberships (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by BIGINT UNSIGNED NULL,
    responded_by BIGINT UNSIGNED NULL,
    invited_at DATETIME NOT NULL,
    responded_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_team_org_memberships_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_team_org_memberships_org FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_team_org_memberships_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT fk_team_org_memberships_responded_by FOREIGN KEY (responded_by) REFERENCES users (id),
    CONSTRAINT uq_team_org UNIQUE (team_id, organization_id),
    CONSTRAINT chk_team_org_memberships_status CHECK (status IN ('PENDING','ACTIVE'))
);
