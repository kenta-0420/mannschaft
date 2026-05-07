-- F12.5 Phase 2: error_reports に運用ワークフロー関連カラムを追加
-- workflow_stage   : 5段階のワークフロー進捗（NULL=未着手）
-- assignee_id      : 担当管理者
-- github_issue_url : 連携した GitHub Issue の URL
-- last_ai_analysis_at : 最終 AI 分析実行日時（バッチ重複実行防止）
ALTER TABLE error_reports
  ADD COLUMN workflow_stage VARCHAR(30) NULL AFTER status,
  ADD COLUMN assignee_id BIGINT UNSIGNED NULL AFTER resolved_by,
  ADD COLUMN github_issue_url VARCHAR(500) NULL AFTER admin_note,
  ADD COLUMN last_ai_analysis_at DATETIME NULL AFTER github_issue_url,
  ADD CONSTRAINT fk_error_reports_assignee_id
    FOREIGN KEY (assignee_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_error_reports_workflow_stage ON error_reports(workflow_stage);
CREATE INDEX idx_error_reports_assignee_id ON error_reports(assignee_id);
CREATE INDEX idx_error_reports_status_workflow ON error_reports(status, workflow_stage);

-- 既存レコードを AI バッチが全件再分析しないよう backfill
UPDATE error_reports SET last_ai_analysis_at = NOW() WHERE last_ai_analysis_at IS NULL;
