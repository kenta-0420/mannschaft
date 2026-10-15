-- 通知 fan-out 抜本改修 P1: 村受信者のキーセットページング用 被覆索引
--
-- 村行事の還流通知は「現役 USER メンバー（left_at IS NULL / banned_at IS NULL）」を
-- subject_id 昇順・カーソル（subject_id > :cursor）・LIMIT chunk でチャンク送りする
-- （メモリ有界なストリーム配信）。この走査を index-only で捌けるよう被覆索引を追加する。
--
-- 既存の UNIQUE 索引（V9.126 系）は banned_at を含まないためキーセット走査を被覆できない。
-- 本索引は WHERE の等値列（village_id, subject_type）→ 範囲/フィルタ列（left_at, banned_at）→
-- ソート/カーソル列（subject_id）の順で並べ、現役判定とページングを 1 本で満たす。
--
-- 同一ドメイン内・追加のみ・低リスク（DROP/変更なし）。クロスドメイン FK は張らない（原則1）。
CREATE INDEX idx_vm_fanout_keyset
    ON village_memberships (village_id, subject_type, left_at, banned_at, subject_id);
