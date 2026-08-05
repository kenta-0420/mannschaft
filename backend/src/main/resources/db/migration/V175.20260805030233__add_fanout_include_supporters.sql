-- 通知 fan-out 抜本改修 Wave-2（ORG スコープ耐久 fan-out）: 第一陣 Entity/Repo/DDL
--
-- ① notification_fanout_jobs に include_supporters 列を追加する。
--    Wave-1 までの VILLAGE fan-out は SUPPORTER も含めて全員配信していたため、
--    既存行の後方互換を保つよう DEFAULT TRUE（全員配信）とする。
--    冪等ユニークキー uk_fanout_idempotency（scope_type, scope_ref, notification_type, source_event_uuid）
--    には含めない（列追加のみ・キー再定義しない）。
--    enqueue 経路でこの列を実際に受け取る配線（NotificationFanoutJobService）は第三陣の担当。
ALTER TABLE notification_fanout_jobs
    ADD COLUMN include_supporters BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Wave-2: SUPPORTER（応援者）を配信対象に含めるか（既定 TRUE=旧経路と同じ全員配信）';

-- ② ORG 版 keyset native クエリ（UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset）用の
--    被覆補助索引。
--
--    既存索引の棚卸し（本 migration 時点）:
--      - user_roles                 : idx_user_roles_org(organization_id) / idx_user_roles_team(team_id)
--                                      （いずれも単一列・user_id を含まずキーセットの ORDER BY user_id を
--                                      カバーしない）
--      - team_org_memberships        : idx_team_org_memberships_org_id_status(organization_id, status)
--                                      （組織×status の等値絞り込みは既存で被覆済み。team_id 追加による
--                                      covering 化は当クエリの主ボトルネックではないため見送り＝過剰索引回避）
--      - organizations               : parent_organization_id は再帰 CTE の JOIN 列（既存 idx で被覆済み、
--                                      本 migration では変更しない）
--
--    そこで user_roles 側にキーセットの ORDER BY user_id を効かせるための複合索引を最小限追加する
--    （organization_id / team_id のいずれの絞り込み経路でも user_id 昇順レンジスキャンで LIMIT に
--    早期到達できるようにする。TEAM 版 idx_membership_fanout_keyset・V174 と同じ発想）。
CREATE INDEX idx_user_roles_org_user_keyset
    ON user_roles (organization_id, user_id);

CREATE INDEX idx_user_roles_team_user_keyset
    ON user_roles (team_id, user_id);
