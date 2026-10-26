-- ユーザー−ロール割当テーブル
CREATE TABLE user_roles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    team_id BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    scope_key VARCHAR(100) GENERATED ALWAYS AS (COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')) STORED,
    granted_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_user_roles_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_user_roles_org FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users (id),
    CONSTRAINT uq_user_roles_user_scope UNIQUE (user_id, scope_key)
);
CREATE INDEX idx_user_roles_team ON user_roles (team_id);
CREATE INDEX idx_user_roles_org ON user_roles (organization_id);
