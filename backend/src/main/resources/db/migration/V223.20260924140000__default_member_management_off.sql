-- MEMBER の予定・ファイル・投稿管理は、スコープ ADMIN が明示的に許可するまで無効。
-- V2.016 の初期付与は既に適用済みの環境があるため、既存行を更新する。
UPDATE role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
SET rp.is_default = 0
WHERE r.name = 'MEMBER'
  AND p.name IN ('MANAGE_SCHEDULES', 'MANAGE_FILES', 'MANAGE_POSTS');
