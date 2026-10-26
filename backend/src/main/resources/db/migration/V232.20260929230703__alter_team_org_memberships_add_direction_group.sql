-- チーム加盟の双方向化: 起点・グループ・添え書き・更新日時と索引（F01.2.1 §5.3）
-- 既存行は direction=ORG_INVITE（DEFAULT）・group_id NULL（未分類）になる
ALTER TABLE team_org_memberships
    ADD COLUMN direction VARCHAR(20) NOT NULL DEFAULT 'ORG_INVITE' COMMENT '起点: ORG_INVITE=組織からの招待 / TEAM_APPLY=チームからの申請',
    ADD COLUMN group_id BINARY(16) NULL COMMENT 'チームグループID（org_team_groups.id・クロスドメインFKなし）',
    ADD COLUMN message VARCHAR(500) NULL COMMENT '申請・招待時の添え書き（PENDING の間だけ意味を持つ）',
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD CONSTRAINT chk_team_org_memberships_direction CHECK (direction IN ('ORG_INVITE','TEAM_APPLY')),
    ADD INDEX idx_team_org_memberships_org_status_dir (organization_id, status, direction, invited_at),
    ADD INDEX idx_team_org_memberships_team_status_dir (team_id, status, direction),
    ADD INDEX idx_team_org_memberships_org_group_status (organization_id, group_id, status),
    ADD INDEX idx_team_org_memberships_status_invited (status, invited_at);
