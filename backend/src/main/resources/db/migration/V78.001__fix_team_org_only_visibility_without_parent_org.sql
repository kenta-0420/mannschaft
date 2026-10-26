-- F00 可視性フレームワーク修正: 親組織が未設定のチームの visibility を PRIVATE に変更
--
-- 問題:
--   ORGANIZATION_ONLY のチームは F00 が ORGANIZATION_WIDE に写像し、
--   「親組織のメンバーのみ閲覧可能」として判定する。
--   しかし team_org_memberships に行が存在しないチームは親組織が解決できず、
--   team のメンバーであっても全員 VISIBILITY_001 で 403 になる。
--
-- 原因:
--   UI がチーム設定で ORGANIZATION_ONLY を選択可能だが、
--   対応する team_org_memberships が存在しないと全員ロックアウトされる。
--
-- 対処:
--   親組織が未登録の ORGANIZATION_ONLY チームを PRIVATE（SCOPE_AFFILIATED = メンバーのみ閲覧可）に変更する。
--   PRIVATE = F00 で SCOPE_AFFILIATED に写像 → team メンバーが閲覧可能。
UPDATE teams t
    LEFT JOIN team_org_memberships tom ON t.id = tom.team_id
SET t.visibility = 'PRIVATE',
    t.updated_at = NOW()
WHERE t.visibility = 'ORGANIZATION_ONLY'
  AND tom.organization_id IS NULL
  AND t.deleted_at IS NULL;
