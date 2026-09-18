-- CMP-260909-1141: 設計書 F04.9 §2 が要求する SEND_NOTIFICATION を権限カタログへ登録する。
--
-- 背景:
--   docs/features/F04.9_confirmable_notification.md §2 は
--   「DEPUTY_ADMIN は SEND_NOTIFICATION 権限を持つ場合のみ確認通知を送信できる」と定めているが、
--   'SEND_NOTIFICATION' はリポジトリ全体（SQL・Java とも）に 1 件も存在しなかった（実測）。
--   BE の confirmable 系コントローラは checkAdminOrAbove（ADMIN / DEPUTY_ADMIN を無条件許可）で
--   ゲートしており、権限判定は 1 行も実装されていなかった。
--   権限名の正本は Flyway の INSERT INTO permissions のみ（docs/security/README.md §4.3）であり、
--   カタログに無い名前で判定を書いても例外にはならず静かに「不成立」になる。
--   よって仕様どおりの委任を成立させる前提として、まずカタログへ登録する。
--
-- 手本: V214.20260916131000__add_manage_surveys_to_catalog.sql。
--   permissions カラム:      id / name / display_name / scope / created_at / updated_at
--   role_permissions カラム: id / role_id / permission_id / is_default / created_at
--   ※ permissions.name は UNIQUE（uq_permissions_name）。scope は
--     CHECK (scope IN ('PLATFORM','ORGANIZATION','TEAM')) で 1 値しか保持できず、
--     認可判定の native クエリ（UserRoleRepository.countDeputyAdminWithPermissionIn*）は
--     p.name だけで照合し p.scope を一切参照しない（権限一覧 UI 向けの分類列）。
--     確認通知は TEAM・ORGANIZATION の両方に立つが、第一義スコープとして 'TEAM' を採る。

-- ============================================================================
-- 1. permissions カタログへ登録（再実行安全: 既に存在すれば追加しない）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'SEND_NOTIFICATION', '確認通知の送信', 'TEAM', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'SEND_NOTIFICATION');

-- ============================================================================
-- 2. ADMIN へ is_default=1 で自動付与
-- ============================================================================
-- 呼び出し側には ADMIN ロールによるバイパスがあるためこの行が無くても管理者は操作できるが、
-- カタログは「その役職が能力を持つ」という設計事実を表す台帳であり、権限一覧 UI もこれを読む。
-- role_id は決して数値直書きせず roles.name で解決する。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, UTC_TIMESTAMP()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'SEND_NOTIFICATION'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 3. DEPUTY_ADMIN へ is_default=1 で既定付与（マスター裁可・意図的）
-- ============================================================================
-- V214（MANAGE_SURVEYS）は DEPUTY_ADMIN への行を「意図的に作らない」方針を採ったが、
-- 本件は逆の判断である。理由:
--   確認通知の送信は既に本番で DEPUTY_ADMIN が checkAdminOrAbove 経由で実行できており、
--   権限判定を後から入れるだけでは「画面は見えるが押すと 403」という退行を新たに作ってしまう。
--   よって初期状態では従来どおり全 DEPUTY_ADMIN が送信できるようにし、
--   絞り込みたい組織は role_permissions / permission_groups 側で外す運用とする。
-- 認可判定は rp.is_default = 1 の行のみを実付与とみなす
--   （UserRoleRepository.countDeputyAdminWithPermissionInTeam / ...InOrganization）ため、
--   ここは 0（天井行）ではなく 1 でなければ意味を成さない。
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, UTC_TIMESTAMP()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name = 'SEND_NOTIFICATION'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============================================================================
-- 4. MEMBER / SUPPORTER / GUEST にはエントリを作成しない（安全側設計）
-- ============================================================================
