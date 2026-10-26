-- F00.5 Phase 4 ロールバック: memberships から user_roles へ MEMBER/SUPPORTER 行を復元する。
-- 緊急時のみ手動実行（Flyway 管理外）。

INSERT INTO user_roles (user_id, role_id, team_id, organization_id, granted_by, created_at, updated_at)
SELECT
    m.user_id,
    r.id AS role_id,
    CASE WHEN m.scope_type = 'TEAM' THEN m.scope_id ELSE NULL END AS team_id,
    CASE WHEN m.scope_type = 'ORGANIZATION' THEN m.scope_id ELSE NULL END AS organization_id,
    m.invited_by AS granted_by,
    m.joined_at AS created_at,
    NOW() AS updated_at
FROM memberships m
JOIN roles r ON r.name = CASE
    WHEN m.role_kind = 'MEMBER' THEN 'MEMBER'
    WHEN m.role_kind = 'SUPPORTER' THEN 'SUPPORTER'
END
WHERE m.left_at IS NULL
  AND m.user_id IS NOT NULL
ON DUPLICATE KEY UPDATE updated_at = NOW();
