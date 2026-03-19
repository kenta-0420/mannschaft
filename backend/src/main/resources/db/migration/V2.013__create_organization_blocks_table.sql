-- 組織ブロックテーブル
CREATE TABLE organization_blocks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    blocked_by BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_org_blocks_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_org_blocks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_org_blocks_blocked_by FOREIGN KEY (blocked_by) REFERENCES users (id),
    CONSTRAINT uq_org_blocks UNIQUE (organization_id, user_id)
);
