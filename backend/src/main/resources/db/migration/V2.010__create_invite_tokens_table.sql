-- 招待トークンテーブル
CREATE TABLE invite_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token CHAR(36) NOT NULL,
    team_id BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    expires_at DATETIME NULL,
    max_uses INT NULL,
    used_count INT NOT NULL DEFAULT 0,
    revoked_at DATETIME NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_invite_tokens_token UNIQUE (token),
    CONSTRAINT fk_invite_tokens_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_invite_tokens_org FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_invite_tokens_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_invite_tokens_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_invite_tokens_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL)
    )
);
