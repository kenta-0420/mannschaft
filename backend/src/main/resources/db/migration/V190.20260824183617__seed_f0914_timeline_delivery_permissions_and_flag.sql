-- F09.14 第一陣: 有料タイムライン配信の認可カタログと既定 OFF のゲート。
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'SEND_PAID_TIMELINE', '有料タイムライン投稿', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'SEND_PAID_TIMELINE');

INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'VIEW_TIMELINE_COST', 'タイムライン配信費用の閲覧', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'VIEW_TIMELINE_COST');

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('SEND_PAID_TIMELINE', 'VIEW_TIMELINE_COST')
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO feature_flags (flag_key, is_enabled, description, created_at, updated_at)
SELECT 'F09_14_TIMELINE_PAID_DELIVERY_ENABLED', 0, 'F09.14 有料タイムライン配信（既定 OFF）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM feature_flags WHERE flag_key = 'F09_14_TIMELINE_PAID_DELIVERY_ENABLED');
