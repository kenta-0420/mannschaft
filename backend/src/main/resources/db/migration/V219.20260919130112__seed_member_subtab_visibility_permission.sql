-- V219: CMP-260919-1140 Phase 1 MEMBER_SUBTAB_VISIBILITY_MANAGE 権限を追加
-- ADMIN にはデフォルト付与、DEPUTY_ADMIN は天井のみ（実付与は ADMIN が permission_groups 経由で行う）
-- MEMBER / SUPPORTER / GUEST にはエントリなし（絶対に付与不可）
--
-- 参考マイグレーション: V9.071 (MANAGE_FRIEND_TEAMS 同パターン)
-- 設計書: docs/features/F06.6_member_subtab_visibility.md §4

INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MEMBER_SUBTAB_VISIBILITY_MANAGE', 'メンバーサブタブ可視性管理', 'ORGANIZATION', UTC_TIMESTAMP(), UTC_TIMESTAMP());

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, UTC_TIMESTAMP()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'MEMBER_SUBTAB_VISIBILITY_MANAGE';

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, UTC_TIMESTAMP()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'MEMBER_SUBTAB_VISIBILITY_MANAGE';

-- MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（絶対に付与不可 — 安全側設計）
