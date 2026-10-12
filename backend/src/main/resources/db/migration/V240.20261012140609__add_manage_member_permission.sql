-- CMP-261012-0313: MANAGE_MEMBER パーミッションをカタログへ登録し ADMIN へ自動付与する
--
-- 背景:
--   F07.5（スキル・資格管理）§2 は、スキルマトリクス・他メンバーの資格検索・CSV エクスポートを
--   「ADMIN、または MANAGE_MEMBER 権限を持つ DEPUTY_ADMIN」に限ると定めている。
--   実装側は AccessControlService.checkAdminOrHasPermissionInScope へ権限名 'MANAGE_MEMBER' を渡すが、
--   permissions カタログにこの名前が無ければ判定は例外にならず静かに不成立になる
--   （docs/security/README.md §4.3 ルール1）。本マイグレーションはその登録である。
--
-- 手本: V183.20260813045816（MANAGE_RECRUITMENTS）
--   permissions カラム:      id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--   permissions.name は UNIQUE（uq_permissions_name）。scope は権限一覧 UI 向けの分類列で
--   認可判定では参照されない。資格閲覧の第一義スコープがチームであるため 'TEAM' を採る。

-- ============================================================================
-- 1. permissions カタログへ登録（再実行安全: 既に存在すれば追加しない）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_MEMBER', 'メンバー情報（資格）の閲覧・エクスポート', 'TEAM', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_MEMBER');

-- ============================================================================
-- 2. ADMIN へ is_default=1 で自動付与
-- ============================================================================
-- 呼び出し側は ADMIN をロールで先に通すため、この行が無くても管理者は操作できる。
-- それでも登録するのは、カタログが「その役職が能力を持つ」という設計事実を表す台帳であり、
-- 権限一覧 UI もこれを読むためである（V184.20260814202646 と同じ方針）。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, UTC_TIMESTAMP()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_MEMBER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 3. DEPUTY_ADMIN には role_permissions 行を作らない
-- ============================================================================
-- F07.5 §2 は「MANAGE_MEMBER 権限を持つ場合」と個別付与を前提にしている。
-- チームスコープの RoleService.hasPermission は role_permissions を is_default で絞らずに集約するため、
-- 天井行（is_default=0）を置くと付与していない DEPUTY_ADMIN 全員が黙って権限を得る（V183 と同じ理由）。
-- DEPUTY_ADMIN への付与は permission_groups（権限グループ画面）経由に一本化する。
-- MEMBER / SUPPORTER / GUEST にもエントリを作成しない。
