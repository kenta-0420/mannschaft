-- =============================================================================
-- V73.001: memberships バックフィル（権限ロール保持ユーザーの取り残しデータ救済）
--
-- 背景:
--   F00.5 で認可の真実の源が memberships テーブルへ切り替わった（AccessControlService.isMember
--   が memberships を参照する）。しかし、チーム/組織の作成や ADMIN/MEMBER 付与などの
--   write-path の一部が user_roles にしか書き込まず memberships を作成しない実装だったため、
--   在籍を表す権限ロール（ADMIN/DEPUTY_ADMIN/MEMBER）を保持していても memberships に
--   アクティブ行が存在しないユーザーが発生し、当該スコープから 403 で締め出されている。
--
-- 対処:
--   現在 user_roles に残る在籍系ロール行（ADMIN / DEPUTY_ADMIN / MEMBER）のうち、
--   team_id または organization_id が設定されているもの（スコープ付き行）に対して、
--   対応する memberships 行を冪等的に INSERT する。
--
-- role_kind マッピング根拠:
--   ADMIN / DEPUTY_ADMIN / MEMBER はいずれも組織/チームの「在籍者」であるため
--   memberships の role_kind = 'MEMBER' にマップする（権限の細分は引き続き user_roles が担い、
--   memberships は在籍有無のみを表す）。
--   SYSTEM_ADMIN は team_id / organization_id が NULL なので WHERE 句で自然除外される。
--   GUEST は閲覧専用であり「在籍者」ではないため対象外とする。
--   SUPPORTER は memberships 側で別途管理されるため対象外。
--   （実データ確認: user_roles のスコープ付き行は ADMIN / MEMBER のみが現存し、
--    GUEST/DEPUTY_ADMIN/SUPPORTER は 0 件。MEMBER 行は write-path により現存しうるため
--    必ず対象に含める。）
--
-- 冪等性:
--   NOT EXISTS (left_at IS NULL の行) で既にアクティブな memberships が存在する
--   ユーザー×スコープの組み合わせはスキップする。
--
-- UNIQUE 制約注意:
--   V60.004 で uq_memberships_history (user_id, scope_type, scope_id, joined_at)
--   の UNIQUE 制約が存在する。
--   退会済み履歴行と joined_at（= user_roles.created_at）が完全一致する場合に
--   重複キーエラーが発生する可能性がある。
--   本マイグレーションは NOT EXISTS でアクティブ行（left_at IS NULL）が無い行のみを
--   対象とするため、退会→再加入ユーザーで同一 created_at の行が既にある場合は
--   挿入がスキップされず UNIQUE 違反となりうる。
--   その場合は当該ユーザーを手動で確認し、アプリ側の MembershipService 経由で
--   正しい joined_at を設定した membership を作成すること。
-- =============================================================================

-- ============================================================
-- TEAM スコープ: ADMIN / DEPUTY_ADMIN / MEMBER 行の救済
-- ============================================================
-- 理由: 在籍を表す権限ロール（ADMIN/DEPUTY_ADMIN/MEMBER）のスコープ付き行を救済する。
--       SYSTEM_ADMIN は team_id IS NULL なので WHERE で除外。
--       GUEST は閲覧専用のため対象外（在籍者ではない）。
INSERT INTO memberships
    (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'TEAM'          AS scope_type,
    ur.team_id      AS scope_id,
    'MEMBER'        AS role_kind,  -- ADMIN/DEPUTY_ADMIN/MEMBER はすべて在籍者扱いで MEMBER にマップ
    ur.created_at   AS joined_at,  -- user_roles.created_at を加入日時として踏襲
    ur.granted_by   AS invited_by, -- 付与者情報を保持
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.team_id IS NOT NULL          -- TEAM スコープのみ（SYSTEM_ADMIN は team_id=NULL なので除外）
  AND ur.user_id IS NOT NULL          -- 念のため NULL ユーザーを除外
  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN', 'MEMBER')  -- 在籍系ロール。GUEST(閲覧専用)/SUPPORTER は対象外
  AND NOT EXISTS (
      -- アクティブな memberships 行（left_at IS NULL）が既に存在するものはスキップ（冪等性）
      SELECT 1
      FROM memberships m
      WHERE m.user_id     = ur.user_id
        AND m.scope_type  = 'TEAM'
        AND m.scope_id    = ur.team_id
        AND m.left_at IS NULL
  );

-- ============================================================
-- ORGANIZATION スコープ: ADMIN / DEPUTY_ADMIN / MEMBER 行の救済
-- ============================================================
INSERT INTO memberships
    (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'ORGANIZATION'       AS scope_type,
    ur.organization_id   AS scope_id,
    'MEMBER'             AS role_kind,  -- ADMIN/DEPUTY_ADMIN/MEMBER はすべて在籍者扱いで MEMBER にマップ
    ur.created_at        AS joined_at,  -- user_roles.created_at を加入日時として踏襲
    ur.granted_by        AS invited_by, -- 付与者情報を保持
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.organization_id IS NOT NULL   -- ORGANIZATION スコープのみ（SYSTEM_ADMIN は org_id=NULL なので除外）
  AND ur.user_id IS NOT NULL           -- 念のため NULL ユーザーを除外
  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN', 'MEMBER')  -- 在籍系ロール。GUEST(閲覧専用)/SUPPORTER は対象外
  AND NOT EXISTS (
      -- アクティブな memberships 行（left_at IS NULL）が既に存在するものはスキップ（冪等性）
      SELECT 1
      FROM memberships m
      WHERE m.user_id     = ur.user_id
        AND m.scope_type  = 'ORGANIZATION'
        AND m.scope_id    = ur.organization_id
        AND m.left_at IS NULL
  );
