-- CMP-017c キープ変換通知の TEAM MEMBER 以上 全員 fan-out: role_kind 絞り込み付き受信者 keyset 用 被覆索引
--
-- キープ変換通知の母集団は「TEAM スコープの MEMBER 以上（role_kind='MEMBER'）現役メンバー（left_at IS NULL）」を
-- 操作者・作成者を除いて user_id 昇順・カーソル（user_id > :cursor）・LIMIT chunk でチャンク送りする
-- （MembershipRepository.findMemberAndAboveTeamUserIdsByKeysetExcluding）。
--
-- 既存の idx_membership_fanout_keyset (scope_type, scope_id, left_at, user_id) は role_kind を含まないため、
-- role_kind='MEMBER' の絞り込みが索引後のフィルタになり index-only 性が崩れる。等値列（scope_type, scope_id,
-- role_kind）→ フィルタ列（left_at）→ ソート/カーソル列（user_id）の順に並べた専用索引を足し、
-- SUPPORTER 除外込みの受信者走査を index-only で捌けるようにする（V174 の TEAM 版と同型）。
--
-- 同一ドメイン内・追加のみ・低リスク（DROP/変更なし）。クロスドメイン FK は張らない（原則1）。
CREATE INDEX idx_membership_keep_fanout
    ON memberships (scope_type, scope_id, role_kind, left_at, user_id);
