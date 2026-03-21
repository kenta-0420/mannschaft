-- F08.7: MANAGE_TOURNAMENT パーミッションを追加
INSERT INTO permissions (name, description, created_at, updated_at)
VALUES ('MANAGE_TOURNAMENT', '大会・リーグ管理権限', NOW(), NOW());
