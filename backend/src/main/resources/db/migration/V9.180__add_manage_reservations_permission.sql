-- F10.7: MANAGE_RESERVATIONS パーミッションを追加
-- 予約管理権限（スタッフ等が予約の承認・キャンセルを担えるようにするための権限）
-- role_permissions へのデフォルト付与は不要（オプション権限）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_RESERVATIONS', '予約管理権限', 'TEAM', NOW(), NOW());
