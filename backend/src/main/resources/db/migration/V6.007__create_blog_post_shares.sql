-- F06.1: 個人ブログ記事のチーム/組織への共有テーブル
CREATE TABLE blog_post_shares (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_post_id    BIGINT UNSIGNED NOT NULL,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    shared_by       BIGINT UNSIGNED NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bps_post_team (blog_post_id, team_id),
    UNIQUE KEY uq_bps_post_org (blog_post_id, organization_id),
    INDEX idx_bps_team (team_id, created_at DESC),
    INDEX idx_bps_org (organization_id, created_at DESC),
    CONSTRAINT chk_blog_share_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_bps_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_blog_share_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_blog_share_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_bps_shared_by FOREIGN KEY (shared_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
