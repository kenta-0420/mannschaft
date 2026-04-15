-- V9.071: F01.5 フレンドチーム機能 — MANAGE_FRIEND_TEAMS 権限を追加
-- ADMIN にはデフォルト付与、DEPUTY_ADMIN は天井のみ（実付与は ADMIN が permission_groups 経由で行う）
-- MEMBER / SUPPORTER / GUEST にはエントリなし（絶対に付与不可）
--
-- 既存スキーマ確認結果:
--   permissions カラム: id / name / display_name / scope / created_at / updated_at
--     ※ description カラムは存在しないため、設計書 §12 の SQL 例から description は省略
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--
-- 参考マイグレーション: V2.002 (permissions DDL), V2.005 (role_permissions DDL),
--                       V2.015 (seed_permissions), V2.016 (seed_role_permissions)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §12

-- 1. permissions テーブルに MANAGE_FRIEND_TEAMS を追加（TEAM scope）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('MANAGE_FRIEND_TEAMS', 'フレンドチーム管理', 'TEAM', NOW(), NOW());

-- 2. ADMIN に is_default=1 で自動付与（チーム作成時に自動で ADMIN へ付与される）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_FRIEND_TEAMS';

-- 3. DEPUTY_ADMIN に is_default=0 で天井のみ登録（ADMIN が permission_groups 経由で明示付与）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'MANAGE_FRIEND_TEAMS';

-- 4. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（絶対に付与不可 — 安全側設計）
