-- F06.1: ブログタグマスターテーブル
CREATE TABLE blog_tags (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    name            VARCHAR(50) NOT NULL,
    color           VARCHAR(7) NOT NULL DEFAULT '#6B7280',
    post_count      INT UNSIGNED NOT NULL DEFAULT 0,
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bt_name_team (team_id, name),
    UNIQUE KEY uq_bt_name_org (organization_id, name),
    INDEX idx_bt_team_order (team_id, sort_order),
    INDEX idx_bt_org_order (organization_id, sort_order),
    CONSTRAINT chk_bt_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_bt_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_bt_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
