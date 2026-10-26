-- ロール−パーミッション初期割当
-- ADMIN: 全11パーミッション（is_default=1）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN';

-- DEPUTY_ADMIN: 全11パーミッション（is_default=0 — 天井定義、実際の付与は permission_groups で制御）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN';

-- MEMBER: デフォルト3件 + 非デフォルト3件
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MEMBER'
  AND p.name IN ('MANAGE_SCHEDULES', 'MANAGE_FILES', 'MANAGE_POSTS');

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MEMBER'
  AND p.name IN ('DELETE_OTHERS_CONTENT', 'MANAGE_ANNOUNCEMENTS', 'SEND_SAFETY_CONFIRMATION');
