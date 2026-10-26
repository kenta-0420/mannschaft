-- V61.015: F09.13 物件履歴台帳 — PROPERTY_HISTORY_MANAGE / PROPERTY_HISTORY_VIEW 権限を追加
-- Phase 2-α-3: PropertyWorkPackageMaskingService の DEPUTY_ADMIN MANAGE/VIEW 区別を厳密化するため、
--              permission_groups 経由で DEPUTY_ADMIN に明示付与できるよう天井エントリを定義する。
--
-- ADMIN は MANAGE/VIEW ともに is_default=1 で常時付与（チーム作成時に自動付与）。
-- DEPUTY_ADMIN は MANAGE/VIEW ともに is_default=0 で天井のみ登録
--   （実付与は ADMIN が permission_groups にぶら下げて user_permission_groups で割当）。
-- MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（絶対に付与不可 — 安全側設計）。
--
-- 既存スキーマ確認結果（V2.002 / V2.005 / V9.071 と同様）:
--   permissions カラム: id / name / display_name / scope / created_at / updated_at
--     ※ description カラムは存在しないため、display_name のみで意味を表現する
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--
-- 設計書: docs/features/F09.13_property_history.md §5.5 マスキング処理
-- 参考マイグレーション: V2.015 (seed_permissions), V2.016 (seed_role_permissions),
--                       V9.071 (MANAGE_FRIEND_TEAMS), V8.052 (MANAGE_TOURNAMENT)

-- 1. permissions テーブルに PROPERTY_HISTORY_MANAGE / PROPERTY_HISTORY_VIEW を追加（TEAM scope）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('PROPERTY_HISTORY_MANAGE', '物件履歴台帳 — 全項目閲覧・管理（金額・業者連絡先含む）', 'TEAM', NOW(), NOW()),
    ('PROPERTY_HISTORY_VIEW',   '物件履歴台帳 — 閲覧のみ（金額・連絡先マスク）',           'TEAM', NOW(), NOW());

-- 2. ADMIN に MANAGE / VIEW を is_default=1 で自動付与
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('PROPERTY_HISTORY_MANAGE', 'PROPERTY_HISTORY_VIEW');

-- 3. DEPUTY_ADMIN に MANAGE / VIEW を is_default=0 で天井のみ登録
--    ADMIN が permission_groups にぶら下げて user_permission_groups で個別ユーザーへ割当
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name IN ('PROPERTY_HISTORY_MANAGE', 'PROPERTY_HISTORY_VIEW');

-- 4. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（絶対に付与不可 — 安全側設計）
