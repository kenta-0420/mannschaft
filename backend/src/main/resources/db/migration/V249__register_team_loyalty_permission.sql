-- TEAM内での営業操作。組織権限やSYSTEM_ADMIN全体権限は転用しない。
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('TEAM_LOYALTY_OPERATE', 'チームポイント受付・交換・承認', 'TEAM', NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r CROSS JOIN permissions p
WHERE r.name='ADMIN' AND p.name='TEAM_LOYALTY_OPERATE';

-- DEPUTY_ADMINへのrole_permissions登録は行わず、permission_groupsによる明示委任だけを使用する。
