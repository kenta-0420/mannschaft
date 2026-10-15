-- 通知 fan-out 抜本改修 Wave-1（TEAM 耐久 fan-out）: memberships 受信者のキーセットページング用 被覆索引
--
-- TEAM スコープの fan-out は「現役メンバー（scope_type='TEAM' / scope_id=対象チーム / left_at IS NULL）」を
-- user_id 昇順・カーソル（user_id > :cursor）・LIMIT chunk でチャンク送りする（メモリ有界なストリーム配信）。
-- この走査を index-only で捌けるよう被覆索引を追加する（村の idx_vm_fanout_keyset と同型・V170）。
--
-- 列順は WHERE の等値列（scope_type, scope_id）→ 範囲/フィルタ列（left_at）→
-- ソート/カーソル列（user_id）の順で並べ、現役判定とキーセットページングを 1 本で満たす。
--
-- 同一ドメイン内・追加のみ・低リスク（DROP/変更なし）。クロスドメイン FK は張らない（原則1）。
CREATE INDEX idx_membership_fanout_keyset
    ON memberships (scope_type, scope_id, left_at, user_id);
