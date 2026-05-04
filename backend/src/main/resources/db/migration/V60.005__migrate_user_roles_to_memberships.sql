-- F00.5 メンバーシップ基盤再設計 Phase 2: 旧 user_roles → 新 memberships への移送 DML
--
-- 設計書: docs/features/F00.5_membership_basis.md §13.2
--
-- 既存の user_roles に格納されている MEMBER / SUPPORTER 行を memberships へコピーする。
-- SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST 行は移送しない（user_roles に残置）。
--
-- マッピング（§13.1）:
--   user_id -> user_id
--   roles.name=SUPPORTER -> 'SUPPORTER' / それ以外（MEMBER）-> 'MEMBER'
--   team_id IS NOT NULL -> scope_type='TEAM', scope_id=team_id
--   organization_id IS NOT NULL -> scope_type='ORGANIZATION', scope_id=organization_id
--   granted_by -> invited_by
--   created_at -> joined_at（および created_at）
--   updated_at -> updated_at
--   left_at / leave_reason / gdpr_masked_at は NULL（移送時点で全員アクティブ前提）
--
-- 注意:
--   - Phase 4 までは user_roles 側にも MEMBER/SUPPORTER 行が残る（二重書き込み戦略）
--   - 重複（同一 user × scope に MEMBER + SUPPORTER 行など）は uq_memberships_active で
--     検出される。事前に EC-17 のとおり運用者が手動マージする想定

-- TEAM スコープの MEMBER / SUPPORTER を memberships へ
INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'TEAM' AS scope_type,
    ur.team_id AS scope_id,
    CASE WHEN r.name = 'SUPPORTER' THEN 'SUPPORTER' ELSE 'MEMBER' END AS role_kind,
    ur.created_at AS joined_at,
    ur.granted_by AS invited_by,
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.team_id IS NOT NULL
  AND r.name IN ('MEMBER', 'SUPPORTER');

-- ORGANIZATION スコープの MEMBER / SUPPORTER を memberships へ
INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, invited_by, created_at, updated_at)
SELECT
    ur.user_id,
    'ORGANIZATION' AS scope_type,
    ur.organization_id AS scope_id,
    CASE WHEN r.name = 'SUPPORTER' THEN 'SUPPORTER' ELSE 'MEMBER' END AS role_kind,
    ur.created_at AS joined_at,
    ur.granted_by AS invited_by,
    ur.created_at,
    ur.updated_at
FROM user_roles ur
JOIN roles r ON r.id = ur.role_id
WHERE ur.organization_id IS NOT NULL
  AND r.name IN ('MEMBER', 'SUPPORTER');
