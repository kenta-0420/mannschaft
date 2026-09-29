-- F01.2.1 5-A: 組織への加盟操作権限 MANAGE_ORG_AFFILIATION を追加する。ADMIN の既定のみ is_default=1（DEPUTY_ADMIN・MEMBER の天井行は作らない）。
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_ORG_AFFILIATION', '組織への加盟操作', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_ORG_AFFILIATION');

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_ORG_AFFILIATION'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
