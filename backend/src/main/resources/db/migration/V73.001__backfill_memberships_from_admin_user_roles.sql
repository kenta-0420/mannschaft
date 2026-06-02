-- =============================================================================
-- V73.001: memberships バックフィル（権限ロール保持ユーザーの取り残しデータ救済）
--
-- 背景:
--   F00.5 Phase 2 (V60.005) で user_roles の MEMBER/SUPPORTER 行を memberships に
--   移送し、Phase 4 (V60.010) で user_roles から MEMBER/SUPPORTER 行を物理削除した。
--   しかし、チーム/組織の作成や ADMIN 付与などの write-path の一部が user_roles
--   にしか書き込まず、memberships を作成しない実装だったため、ADMIN/DEPUTY_ADMIN 等の
--   権限ロールのみ保持するユーザーは memberships に行が存在しない状態になっている。
--
--   これにより AccessControlService.isMember() が memberships を参照するため、
--   ADMIN/DEPUTY_ADMIN であっても一般メンバーとして認識されず 403 が返る問題が発生する。
--
-- 対処:
--   現在 user_roles に残る権限ロール行（ADMIN / DEPUTY_ADMIN / GUEST）のうち、
--   team_id または organization_id が設定されているもの（スコープ付き行）に対して、
--   対応する memberships 行を冪等的に INSERT する。
--
-- role_kind マッピング根拠:
--   ADMIN / DEPUTY_ADMIN / GUEST はすべて組織/チームの「在籍者」であるため
--   role_kind = 'MEMBER' にマップする。
--   SYSTEM_ADMIN は team_id / organization_id が NULL なので WHERE 句で自然除外される。
--   MEMBER / SUPPORTER は V60.010 で物理削除済みのため user_roles には存在しない。
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
-- TEAM スコープ: ADMIN / DEPUTY_ADMIN / GUEST 行の救済
-- ============================================================
-- 理由: V60.010 で MEMBER/SUPPORTER は削除済み。現在 user_roles に残るのは
--       ADMIN / DEPUTY_ADMIN / SYSTEM_ADMIN / GUEST のみ。
--       SYSTEM_ADMIN は team_id IS NOT NULL の WHERE で除外される。
INSERT INTO memberships
    (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'TEAM'          AS scope_type,
    ur.team_id      AS scope_id,
    'MEMBER'        AS role_kind,  -- ADMIN/DEPUTY_ADMIN/GUEST はすべて在籍者扱いで MEMBER にマップ
    ur.created_at   AS joined_at,  -- user_roles.created_at を加入日時として踏襲
    ur.granted_by   AS invited_by, -- 付与者情報を保持
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.team_id IS NOT NULL          -- TEAM スコープのみ（SYSTEM_ADMIN は team_id=NULL なので除外）
  AND ur.user_id IS NOT NULL          -- 念のため NULL ユーザーを除外
  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN', 'GUEST')  -- V60.010 で削除済みの MEMBER/SUPPORTER は対象外
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
-- ORGANIZATION スコープ: ADMIN / DEPUTY_ADMIN / GUEST 行の救済
-- ============================================================
INSERT INTO memberships
    (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'ORGANIZATION'       AS scope_type,
    ur.organization_id   AS scope_id,
    'MEMBER'             AS role_kind,  -- ADMIN/DEPUTY_ADMIN/GUEST はすべて在籍者扱いで MEMBER にマップ
    ur.created_at        AS joined_at,  -- user_roles.created_at を加入日時として踏襲
    ur.granted_by        AS invited_by, -- 付与者情報を保持
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.organization_id IS NOT NULL   -- ORGANIZATION スコープのみ（SYSTEM_ADMIN は org_id=NULL なので除外）
  AND ur.user_id IS NOT NULL           -- 念のため NULL ユーザーを除外
  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN', 'GUEST')  -- V60.010 で削除済みの MEMBER/SUPPORTER は対象外
  AND NOT EXISTS (
      -- アクティブな memberships 行（left_at IS NULL）が既に存在するものはスキップ（冪等性）
      SELECT 1
      FROM memberships m
      WHERE m.user_id     = ur.user_id
        AND m.scope_type  = 'ORGANIZATION'
        AND m.scope_id    = ur.organization_id
        AND m.left_at IS NULL
  );
