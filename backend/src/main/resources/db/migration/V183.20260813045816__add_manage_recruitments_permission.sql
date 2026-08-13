-- F03.11 / F22.1: MANAGE_RECRUITMENTS パーミッションをカタログへ登録し ADMIN へ自動付与する
--
-- 背景:
--   F03.11 §13「ロール別デフォルト保有」および F22.1 §04_security §1.1 は、募集（札）および
--   その決済（Connect）の管理操作を「ADMIN は自動付与 / DEPUTY_ADMIN は手動付与」と定めており、
--   実装側も AccessControlService へ権限名 'MANAGE_RECRUITMENTS' を渡している。
--   しかし permissions カタログに当該権限を登録するマイグレーションが存在せず、
--   TEAM スコープの判定経路（RoleService.hasPermission = role_permissions ∪ permission_groups）が
--   参照先を持たないため、設計が意図した判定そのものが成立していなかった。
--   本マイグレーションはその定義漏れを埋める。
--
-- 参考マイグレーション: V9.071（MANAGE_FRIEND_TEAMS）, V9.158（F18 残高型権限）
--   permissions カラム:      id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--   ※ permissions.name は UNIQUE（uq_permissions_name）。scope は
--     CHECK (scope IN ('PLATFORM','ORGANIZATION','TEAM')) で 1 値のみ保持でき、
--     認可判定では参照されない（権限一覧 UI 向けの分類列）。募集（札）の第一義スコープが
--     チームであるため 'TEAM' を採る。組織スコープでの判定は
--     AccessControlService.checkAdminOrHasPermission が同じ name を引くため影響しない。

-- ============================================================================
-- 1. permissions カタログへ登録（再実行安全: 既に存在すれば追加しない）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_RECRUITMENTS', '募集（札）管理', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_RECRUITMENTS');

-- ============================================================================
-- 2. ADMIN へ is_default=1 で自動付与（F03.11 §13「ADMIN: 自動付与」）
-- ============================================================================
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_RECRUITMENTS'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 3. DEPUTY_ADMIN には role_permissions 行を作らない（F03.11 §13「DEPUTY_ADMIN: 手動付与」）
-- ============================================================================
-- V9.071 / V9.158 は DEPUTY_ADMIN へ is_default=0 の「天井」行を登録する形を採っているが、
-- 本権限では意図的にそれを行わない。理由は判定経路が 2 系統あり意味論が異なるためである:
--   - 組織スコープ: AccessControlService.checkAdminOrHasPermission →
--       UserRoleRepository.existsDeputyAdminWithPermissionInOrganization が
--       `rp.is_default = 1` を条件に含むため、天井行（is_default=0）は自動付与と誤解されない。
--   - チームスコープ: AccessControlService.checkPermission → RoleService.hasPermission →
--       RoleService.resolveEffectivePermissions は role_permissions を is_default で絞らずに
--       すべて集約する。ここへ天井行を置くと、権限を個別付与していない DEPUTY_ADMIN 全員が
--       黙って募集・決済の管理権限を得てしまう（F03.11 §13「手動付与」に反する）。
-- したがって DEPUTY_ADMIN への付与は permission_groups（権限グループ画面）経由に一本化する。
-- MEMBER / SUPPORTER / GUEST にもエントリを作成しない（安全側設計）。
