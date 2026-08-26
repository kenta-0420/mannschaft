-- F10.1.1 P3b Wave3: 管理者レンズ「予算ウィジェット」点火に伴う TEAM スコープ予算閲覧権限の seed。
--
-- 背景:
--   既存の予算閲覧権限 BUDGET_VIEW / BUDGET_MANAGE は scope='ORGANIZATION' で seed 済み（V11.034）。
--   管理者レンズの予算ウィジェットは team / org 両スコープで点火するが、TEAM スコープでは
--   AccessControlService.checkAdminOrHasPermission（ORGANIZATION 専用）が使えないため、
--   Facade 側で isAdmin(TEAM) || hasPermission(TEAM, "TEAM_BUDGET_VIEW") を明示判定する。
--   その judging に使う TEAM スコープ専用の権限 TEAM_BUDGET_VIEW / TEAM_BUDGET_MANAGE を新設する。
--
-- 命名:
--   permissions.name は単独 UNIQUE（V2.002 uq_permissions_name）であり、ORGANIZATION の
--   BUDGET_VIEW と同名の TEAM 行は作れない。よって TEAM_ プレフィックス付きの別名を採用する。
--
-- 冪等性:
--   - permissions は INSERT IGNORE（UNIQUE(name) 違反時スキップ）。
--   - role_permissions は WHERE NOT EXISTS で重複付与を防ぐ。
--   既存の ORGANIZATION BUDGET_VIEW / BUDGET_MANAGE 行には一切触れない（追加のみ）。
--
-- 付与方針（V11.034 / V18.015 と同パターン）:
--   - ADMIN          : is_default=1（実際付与）。管理者は常に TEAM 予算を閲覧できる。
--   - DEPUTY_ADMIN   : is_default=0（天井定義）。permission_groups 経由で組織が個別付与する。
--   ※ ウィジェットの実認可は Facade（isAdmin || hasPermission）で行うため、ここでは ADMIN/DEPUTY のみを対象とする。

-- ====================================================================
-- (1) permissions seed（TEAM スコープ）
-- ====================================================================
INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('TEAM_BUDGET_VIEW',   'チーム予算閲覧',   'TEAM', NOW(), NOW()),
    ('TEAM_BUDGET_MANAGE', 'チーム予算管理',   'TEAM', NOW(), NOW());

-- ====================================================================
-- (2) ADMIN に TEAM_BUDGET_VIEW / TEAM_BUDGET_MANAGE をデフォルト付与（is_default=1）
-- ====================================================================
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('TEAM_BUDGET_VIEW', 'TEAM_BUDGET_MANAGE')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ====================================================================
-- (3) DEPUTY_ADMIN に TEAM_BUDGET_VIEW / TEAM_BUDGET_MANAGE を天井付与（is_default=0）
--     permission_groups 経由で組織が個別に付与できる前提の天井定義。
-- ====================================================================
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name IN ('TEAM_BUDGET_VIEW', 'TEAM_BUDGET_MANAGE')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
