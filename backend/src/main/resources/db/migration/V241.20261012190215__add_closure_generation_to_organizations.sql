-- F01.2.1 2-D（§4.5）: 組織の「閉鎖の世代番号」。アーカイブ・論理削除（＝閉鎖）のたびに、組織行のロックの下で +1 する
-- （OrganizationEntity#archive・#softDelete。OrganizationService#archiveOrganization・#deleteOrganization が
-- findByIdForUpdate で組織行をロックしてから呼ぶ）。アーカイブ解除・論理削除の取消（復元）では変えない。
-- 加盟の PENDING（team_org_memberships）は作成時点の組織の世代を org_closure_generation（V242）に記録し、
-- 「記録した世代 < いまの世代」の PENDING を「閉鎖より前に作られた（閉鎖前の）PENDING」として、片付け・回復バッチ・
-- 承認・承諾のすべてで無効として扱う（解除で復元しない）。時刻の比較（作成日時と閉鎖時刻）は DATETIME の秒精度・丸めで
-- 同じ秒の前後を見分けられないので使わない（2-D 検分4巡目）。世代は整数の比較なので精度・タイムゾーンに依存しない。
ALTER TABLE organizations
    ADD COLUMN closure_generation BIGINT NOT NULL DEFAULT 0
        COMMENT '閉鎖の世代番号。アーカイブ・論理削除のたびに +1（解除・復元では変えない）';

-- 既存の行の補完: いま閉じている（アーカイブ済み・論理削除済み）組織を 1 にする。既存の加盟の PENDING は
-- org_closure_generation が既定値 0 で入る（V242）ので、閉じている組織に残る PENDING は「0 < 1」で閉鎖前と判定され、
-- 片付け・回復バッチの対象になり、承認・承諾もできない（解除・復元しても復元しない）。いま開いている組織は 0 のままで、
-- その PENDING（0）は閉鎖前と見なさない。過去に閉じて解除・復元済みの組織の閉鎖の履歴は残っていないので数えない
-- （本番に既存データは無い前提）。
UPDATE organizations
SET closure_generation = 1
WHERE archived_at IS NOT NULL OR deleted_at IS NOT NULL;
