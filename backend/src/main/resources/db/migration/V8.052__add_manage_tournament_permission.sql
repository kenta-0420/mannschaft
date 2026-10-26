-- F08.7: MANAGE_TOURNAMENT パーミッションを追加
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_TOURNAMENT', '大会・リーグ管理権限', 'TEAM', NOW(), NOW());
