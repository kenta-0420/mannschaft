-- F02.2.1: DASHBOARD_WIDGET_VISIBILITY_MANAGE パーミッションを追加
--
-- ADMIN は無条件で可視性設定を変更できるため is_default=1 で自動付与。
-- DEPUTY_ADMIN は ADMIN が permission_groups 経由で明示付与した場合のみ可とする保守的な初期値とし、
-- 天井エントリ（is_default=0）のみを登録する。
-- MEMBER / SUPPORTER / GUEST には付与不可（エントリも作らない）。
--
-- 設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §4, §9
-- 既存スキーマ:
--   permissions カラム: id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
-- 参考: V9.071__add_manage_friend_teams_permission.sql

-- 1. permissions テーブルに DASHBOARD_WIDGET_VISIBILITY_MANAGE を追加（TEAM scope）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('DASHBOARD_WIDGET_VISIBILITY_MANAGE', 'ダッシュボードウィジェット可視性管理', 'TEAM', NOW(), NOW());

-- 2. ADMIN に is_default=1 で自動付与
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'DASHBOARD_WIDGET_VISIBILITY_MANAGE';

-- 3. DEPUTY_ADMIN に is_default=0 で天井のみ登録（ADMIN が permission_groups 経由で明示付与）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'DASHBOARD_WIDGET_VISIBILITY_MANAGE';

-- 4. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（絶対に付与不可 — 安全側設計）
