-- F08.3: 議決権行使・委任状 — MANAGE_VOTES パーミッション追加
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_VOTES', '議決権行使・委任状の管理', 'ORGANIZATION', NOW(), NOW());

-- role_permissions へのシード追加はアプリ起動後に管理画面から設定
