-- F09.15 S1-A: MANAGE_SUCCESSION_UNSEAL 権限の追加
-- 設計書: docs/features/F09.15_resident_succession_support.md §9.6
--
-- 封緘解除二者承認に必要な権限。
-- DEPUTY_ADMIN（副理事長）へ理事長が個別付与可能とする設計のため
--   ADMIN: is_default=1（自動付与・自動的に承認権限を持つ）
--   DEPUTY_ADMIN: is_default=0（天井のみ・理事長が permission_groups 経由で明示付与）
--   MEMBER / SUPPORTER / GUEST: 天井エントリも作成しない（絶対に付与不可）
--
-- 参考: V9.071__add_manage_friend_teams_permission.sql / V8.052__add_manage_tournament_permission.sql

-- 1. permissions テーブルに MANAGE_SUCCESSION_UNSEAL を追加（ORGANIZATION scope）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_SUCCESSION_UNSEAL', '区分所有者承継・封緘解除承認', 'ORGANIZATION', NOW(), NOW());

-- 2. ADMIN に is_default=1 で自動付与
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_SUCCESSION_UNSEAL';

-- 3. DEPUTY_ADMIN に is_default=0 で天井のみ登録（ADMIN が permission_groups 経由で明示付与）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'MANAGE_SUCCESSION_UNSEAL';

-- 4. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（安全側設計）
