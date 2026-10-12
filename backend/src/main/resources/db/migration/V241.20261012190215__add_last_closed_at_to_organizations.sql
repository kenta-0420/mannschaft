-- F01.2.1 2-D（§4.5）: 組織を最後にアーカイブ・論理削除した時刻を残す。アーカイブ解除・論理削除の取消（復元）では消さない。
-- 加盟の片付け（team ドメインの TeamOrgLifecycleCleanupService）は「いま閉じているか」だけでなく、
-- 「その PENDING が最後の閉鎖より前に作られたか」（team_org_memberships.created_at < last_closed_at）でも判定する。
-- 片付けの前にアーカイブ解除・復元されても、閉鎖より前の申請・招待を残さない（§4.5「アーカイブ解除では PENDING を復元しない」）。
-- 解除後の新しい申請・招待は created_at が last_closed_at より後なので消さない。
-- archived_at・deleted_at・team_org_memberships.created_at と同じ精度（秒）・同じ格納基準（UTC 壁時計）で持ち、
-- 閉鎖の時刻を秒に切り捨てて書く（OrganizationEntity）。閉鎖の後に作られた行の created_at はこれより前にならないので、
-- 厳密に前の行だけを消せば解除後の申請・招待を誤って消さない。
ALTER TABLE organizations
    ADD COLUMN last_closed_at DATETIME NULL
        COMMENT '最後にアーカイブまたは論理削除した時刻（UTC 壁時計）。解除・復元しても消さない';

-- 既存の行: いま閉じている組織だけ、その時刻で埋める（解除・復元済みの組織の過去の閉鎖時刻は残っていないので埋めない）
UPDATE organizations
SET last_closed_at = GREATEST(COALESCE(archived_at, deleted_at), COALESCE(deleted_at, archived_at))
WHERE archived_at IS NOT NULL OR deleted_at IS NOT NULL;
