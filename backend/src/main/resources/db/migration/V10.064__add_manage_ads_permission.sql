-- F09.11: MANAGE_ADS パーミッションを追加
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_ADS', '広告管理権限', 'ORGANIZATION', NOW(), NOW());
