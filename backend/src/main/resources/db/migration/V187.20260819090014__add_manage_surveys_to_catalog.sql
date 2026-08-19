-- CMP-041 第一陣: 設計書が要求する MANAGE_SURVEYS を権限カタログへ登録する
--
-- 背景:
--   docs/features/F05.4_survey_vote.md:320 は「ADMIN+」を
--   「ADMIN、または MANAGE_SURVEYS を持つ DEPUTY_ADMIN」と定義しているが、
--   'MANAGE_SURVEYS' は migration にも Java にも 1 件も存在しない（実測）。
--   設計書が挙げる V5.037__add_manage_surveys_permission.sql は実在せず、
--   実際の V5.037 は form_template_fields の別物である。
--   権限名の正本は Flyway の INSERT INTO permissions のみ（docs/security/README.md §4.3）で
--   あるため、カタログに無い名前で判定を書いても例外にはならず静かに「不成立」になる。
--   仕様どおりの委任を成立させる前提として、まずカタログへ登録する。
--
-- 手本: V184.20260814202646__add_dead_permission_names_to_catalog.sql。
--   permissions カラム:      id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--   ※ permissions.name は UNIQUE（uq_permissions_name）。scope は
--     CHECK (scope IN ('PLATFORM','ORGANIZATION','TEAM')) で 1 値しか保持できず、
--     認可判定では参照されない（権限一覧 UI 向けの分類列）。
--     アンケートは TEAM・ORGANIZATION の両方に立つが、第一義スコープとして 'TEAM' を採る。

-- ============================================================================
-- 1. permissions カタログへ登録（再実行安全: 既に存在すれば追加しない）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_SURVEYS', 'アンケート・投票の管理', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_SURVEYS');

-- ============================================================================
-- 2. ADMIN へ is_default=1 で自動付与（F03.11 §13「ADMIN: 自動付与」）
-- ============================================================================
-- 呼び出し側には ADMIN ロールによるバイパスがあるためこの行が無くても管理者は操作できるが、
-- カタログは「その役職が能力を持つ」という設計事実を表す台帳であり、権限一覧 UI もこれを読む。
-- role_id は決して数値直書きせず roles.name で解決する。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_SURVEYS'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 3. DEPUTY_ADMIN には role_permissions 行を作らない（意図的）
-- ============================================================================
-- V9.071（MANAGE_FRIEND_TEAMS）等の古い形は DEPUTY_ADMIN へ is_default=0 の「天井」行を
-- 置いているが、V184 と同じ理由でここでは行わない。天井行を置くと、判定経路によっては
-- 権限を個別付与していない副管理者全員へ黙って権限が渡ってしまう
-- （RoleService.resolveEffectivePermissions は is_default を参照せず role_permissions の
-- 全行を権限として集約する。docs/security/README.md §4.3）。
-- したがって DEPUTY_ADMIN への付与は permission_groups（権限グループ画面）経由に一本化する。
-- MEMBER / SUPPORTER / GUEST にもエントリを作成しない（安全側設計）。
