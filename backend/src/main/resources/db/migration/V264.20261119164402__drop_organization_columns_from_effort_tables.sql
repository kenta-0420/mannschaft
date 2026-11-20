-- F06.4.1 個人の頑張り記録 PR-2: チーム側を組織非依存へ改める（2026-11-18 マスター裁定）
-- 「チーム内共有が目的であり組織所属を前提にしない。組織単位の共有は作らない」により、
-- effort_definitions.organization_id と effort_records.share_organization_id を削除し、
-- TEAM 定義の索引を team_id 起点へ作り直す。親組織 0 件・複数件のチームも同じ挙動とする。
-- 設計正本: docs/features/F06.4.1_personal_effort_records_data.md §2（本番データなし）

-- effort_definitions: 組織列を含む CHECK を外してから列と索引を作り直す
ALTER TABLE effort_definitions DROP CHECK chk_effort_definition_scope;
ALTER TABLE effort_definitions DROP INDEX idx_effort_definition_team;
ALTER TABLE effort_definitions DROP COLUMN organization_id;
ALTER TABLE effort_definitions ADD KEY idx_effort_definition_team (team_id, deleted_at);
ALTER TABLE effort_definitions ADD CONSTRAINT chk_effort_definition_scope CHECK (
  (source_type = 'PRESET' AND team_id IS NULL AND team_template_id IS NULL)
  OR (source_type = 'TEAM' AND team_id IS NOT NULL AND team_template_id IS NOT NULL));

-- effort_records: share 組の CHECK を組織列なしで再定義する
ALTER TABLE effort_records DROP CHECK chk_effort_record_share_group;
ALTER TABLE effort_records DROP COLUMN share_organization_id;
ALTER TABLE effort_records ADD CONSTRAINT chk_effort_record_share_group CHECK (
  (share_id IS NULL AND share_team_id IS NULL AND share_state IS NULL)
  OR (share_id IS NOT NULL AND share_team_id IS NOT NULL AND share_state IS NOT NULL
      AND grant_membership_id IS NOT NULL));
