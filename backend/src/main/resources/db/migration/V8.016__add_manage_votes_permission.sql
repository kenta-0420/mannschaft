-- F08.3: 議決権行使・委任状 — MANAGE_VOTES パーミッション追加
INSERT INTO permissions (name, description, scope, created_at, updated_at)
VALUES ('MANAGE_VOTES', '議決権行使・委任状の管理', 'TEAM', NOW(), NOW());

INSERT INTO permissions (name, description, scope, created_at, updated_at)
VALUES ('MANAGE_VOTES', '議決権行使・委任状の管理', 'ORGANIZATION', NOW(), NOW());

-- SYSTEM_ADMIN・ADMIN 用のシードデータ
INSERT INTO role_permissions (role, permission_name, created_at)
SELECT 'SYSTEM_ADMIN', 'MANAGE_VOTES', NOW()
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions WHERE role = 'SYSTEM_ADMIN' AND permission_name = 'MANAGE_VOTES'
);

INSERT INTO role_permissions (role, permission_name, created_at)
SELECT 'ADMIN', 'MANAGE_VOTES', NOW()
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions WHERE role = 'ADMIN' AND permission_name = 'MANAGE_VOTES'
);
