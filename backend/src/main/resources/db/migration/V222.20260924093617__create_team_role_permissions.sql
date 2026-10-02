-- チーム・組織ごとのロール既定権限上書き。
-- 行が無い権限は role_permissions.is_default を継承する。
CREATE TABLE team_role_permissions (
    id BINARY(16) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    is_enabled TINYINT(1) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_team_role_permissions_scope_role_permission
        UNIQUE (scope_type, scope_id, role_id, permission_id),
    CONSTRAINT fk_team_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_team_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions (id),
    CONSTRAINT chk_team_role_permissions_scope_type
        CHECK (scope_type IN ('TEAM', 'ORGANIZATION')),
    INDEX idx_team_role_permissions_role_permission (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
