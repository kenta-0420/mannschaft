-- CMP-037 第一陣: コードが参照しているのにカタログ未登録だった権限 3 件を permissions へ登録する
--
-- 背景:
--   権限名の正本は Flyway の INSERT INTO permissions のみで、Java 側は各サービスの
--   static final String に直書きされている（docs/security/README.md §4.3）。そのため
--   カタログに無い名前を書いてもコンパイルも起動も通ってしまい、判定は例外ではなく
--   静かに「不成立」になる。以下 3 件がその状態にあった:
--     - VIEW_ATTENDANCE  … ClassHomeroomService（学級担任設定一覧）。当該経路は誰も通れなかった。
--     - MANAGE_COMMITTEE … CommitteeService（委員会設立）。管理者はロールで通るため、
--                          委任を意図した相手だけが通れなかった。
--     - jobs.manage      … JobPolicy（求人投稿・応募採否）。同上。
--
-- 手本: V183.20260813045816（MANAGE_RECRUITMENTS）。
--   permissions カラム:      id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--   ※ permissions.name は UNIQUE（uq_permissions_name）。scope は
--     CHECK (scope IN ('PLATFORM','ORGANIZATION','TEAM')) で 1 値のみ保持でき、
--     認可判定では参照されない（権限一覧 UI 向けの分類列）。各権限の第一義スコープを採る。
--
-- 命名について:
--   'jobs.manage' は他の権限（SCREAMING_SNAKE_CASE）と表記が揃っていないが、本マイグレーションでは
--   コード側の定数（JobPolicy.PERMISSION_MANAGE_JOBS）に合わせて現状の名前のまま登録する。
--   改名はコード変更を伴い認可の是正と混ざるため、別途行う。

-- ============================================================================
-- 1. permissions カタログへ登録（再実行安全: 既に存在すれば追加しない）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'VIEW_ATTENDANCE', '出欠・学級担任情報の閲覧', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'VIEW_ATTENDANCE');

INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_COMMITTEE', '委員会の設立・解散', 'ORGANIZATION', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_COMMITTEE');

INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'jobs.manage', '求人の投稿・応募採否', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'jobs.manage');

-- ============================================================================
-- 2. ADMIN へ is_default=1 で自動付与（F03.11 §13「ADMIN: 自動付与」）
-- ============================================================================
-- 3 件とも呼び出し側にロールによる管理者バイパスがあるため、この行が無くても管理者は操作できる。
-- それでも登録するのは、カタログが「その役職が能力を持つ」という設計事実を表す台帳であり、
-- 権限一覧 UI もこれを読むためである（コード側のバイパスは多重防御であって台帳の代用ではない）。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('VIEW_ATTENDANCE', 'MANAGE_COMMITTEE', 'jobs.manage')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 3. DEPUTY_ADMIN には role_permissions 行を作らない（意図的）
-- ============================================================================
-- V9.071（MANAGE_FRIEND_TEAMS）等の古い形は DEPUTY_ADMIN へ is_default=0 の「天井」行を
-- 置いているが、本 3 件では意図的にそれを行わない。判定経路で is_default の意味が違うためである
-- （docs/security/README.md §4.3）:
--   - AccessControlService.checkAdminOrHasPermission（ORGANIZATION 専用）→
--       UserRoleRepository.existsDeputyAdminWithPermissionInOrganization は
--       `rp.is_default = 1` の行のみを実付与とみなす。
--   - AccessControlService.checkPermission / hasPermission → RoleService.resolveEffectivePermissions は
--       is_default を一切参照せず role_permissions の全行を権限として集約する。
-- 本 3 件はいずれも後者（checkPermission / hasPermission）経路である。ここへ天井行を置くと、
-- 権限を個別付与していない副管理者全員へ黙って権限が渡ってしまう。
-- したがって DEPUTY_ADMIN への付与は permission_groups（権限グループ画面）経由に一本化する。
-- MEMBER / SUPPORTER / GUEST にもエントリを作成しない（安全側設計）。
