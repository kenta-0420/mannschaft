-- user_roles の管理者ロール照会・ロック取得を複合インデックスで支える
CREATE INDEX idx_user_roles_team_role_id
    ON user_roles (team_id, role_id, id);

CREATE INDEX idx_user_roles_organization_role_id
    ON user_roles (organization_id, role_id, id);
