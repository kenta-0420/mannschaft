-- F12.5 Phase 2: 操作履歴・コメントテーブル
-- activity_type: STATUS_CHANGED / WORKFLOW_CHANGED / ASSIGNEE_CHANGED / COMMENT_ADDED /
--                AI_ANALYZED / GITHUB_ISSUE_CREATED / SEVERITY_CHANGED / REOPENED / RESOLVED
CREATE TABLE error_report_activities (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  error_report_id BIGINT UNSIGNED NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  activity_type VARCHAR(40) NOT NULL,
  content VARCHAR(2000) NULL,
  metadata_json VARCHAR(2000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_era_error_report_id FOREIGN KEY (error_report_id) REFERENCES error_reports(id) ON DELETE CASCADE,
  CONSTRAINT fk_era_actor_id FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_era_error_report_id_created ON error_report_activities(error_report_id, created_at DESC);
CREATE INDEX idx_era_activity_type ON error_report_activities(activity_type);
