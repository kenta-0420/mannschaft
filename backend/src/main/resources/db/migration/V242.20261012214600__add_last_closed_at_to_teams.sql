-- F01.2.1 2-D（§4.5）: チームを最後にアーカイブ・論理削除した時刻を残す。アーカイブ解除・論理削除の取消（復元）では消さない。
-- 組織側の organizations.last_closed_at（V241）と同じ規則で、加盟の片付け・承認・承諾が
-- 「その PENDING がチームの最後の閉鎖より前に作られたか」（team_org_memberships.created_at < last_closed_at）を判定する。
-- 他ドメインの tx からのアーカイブ（後継者不在の自動アーカイブ）の片付けはコミット後に行うので、その前に
-- アーカイブ解除（システム管理者の強制解除など）されても、閉鎖より前の申請・招待を復元しない（§4.5）。
-- archived_at・deleted_at・team_org_memberships.created_at と同じ精度（秒）・同じ格納基準（UTC 壁時計）で持ち、
-- 閉鎖の瞬間を秒に切り捨てて書く（TeamEntity）。
ALTER TABLE teams
    ADD COLUMN last_closed_at DATETIME NULL
        COMMENT '最後にアーカイブまたは論理削除した時刻（UTC 壁時計）。解除・復元しても消さない';

-- 既存の行: いま閉じているチームだけ、その時刻で埋める（解除・復元済みのチームの過去の閉鎖時刻は残っていないので埋めない）
UPDATE teams
SET last_closed_at = GREATEST(COALESCE(archived_at, deleted_at), COALESCE(deleted_at, archived_at))
WHERE archived_at IS NOT NULL OR deleted_at IS NOT NULL;
