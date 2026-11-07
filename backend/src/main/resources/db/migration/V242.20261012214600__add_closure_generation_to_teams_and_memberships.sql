-- F01.2.1 2-D（§4.5）: チームの「閉鎖の世代番号」と、加盟の PENDING が作成時点に見た組織・チームの世代。
-- チームも組織（V241）と同じく、アーカイブ・論理削除のたびにチーム行のロックの下で closure_generation を +1 し
-- （TeamEntity#archive・#softDelete。TeamService が findByIdForUpdate でチーム行をロックしてから呼ぶ）、
-- アーカイブ解除・論理削除の取消（復元）では変えない。
ALTER TABLE teams
    ADD COLUMN closure_generation BIGINT NOT NULL DEFAULT 0
        COMMENT '閉鎖の世代番号。アーカイブ・論理削除のたびに +1（解除・復元では変えない）';

-- 加盟行は、作成時点の組織・チームの世代を記録する。申請はチーム行 → 組織行のロックの下で、招待はチーム行のロックの下で
-- 読んだ世代を書く（招待は組織行をロックしないので、コミット後に組織行の共有ロックで読み直し、世代が進んでいれば取り下げる）。
-- 「記録した世代 < いまの世代」の PENDING は閉鎖より前に作られた行で、片付け・回復バッチ・承認・承諾のすべてで無効として扱う。
-- ACTIVE の行でも値は残るが判定には使わない（作成後は変えない）。
ALTER TABLE team_org_memberships
    ADD COLUMN org_closure_generation BIGINT NOT NULL DEFAULT 0
        COMMENT '作成時点の組織の閉鎖の世代（organizations.closure_generation）。これより組織の世代が進んでいれば閉鎖前の行',
    ADD COLUMN team_closure_generation BIGINT NOT NULL DEFAULT 0
        COMMENT '作成時点のチームの閉鎖の世代（teams.closure_generation）。これよりチームの世代が進んでいれば閉鎖前の行';

-- 既存の行の補完: いま閉じている（アーカイブ済み・論理削除済み）チームを 1 にする。既存の加盟行は上の既定値 0 で入るので、
-- 閉じている組織（V241 で 1）・チーム（ここで 1）に残る PENDING は「0 < 1」で閉鎖前と判定され、片付け・回復バッチの対象になり、
-- 承認・承諾もできない（解除・復元しても復元しない）。開いている組織・チームの PENDING は「0 = 0」で閉鎖前と見なさない。
-- 過去に閉じて解除・復元済みのチームの閉鎖の履歴は残っていないので数えない（本番に既存データは無い前提）。
UPDATE teams
SET closure_generation = 1
WHERE archived_at IS NOT NULL OR deleted_at IS NOT NULL;
