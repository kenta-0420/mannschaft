-- F09.16 S1-B: MANAGE_RESIDENCE_STATUS パーミッションを追加
-- 副理事長等が居住実態管理を担えるようにするための権限
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_RESIDENCE_STATUS', '居住実態管理権限', 'ORGANIZATION', NOW(), NOW());
