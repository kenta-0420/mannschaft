-- F12.5 Phase 2: AI 分析履歴テーブル
-- raw_response は 30日後に NULL 化（クリーンアップ対象）
CREATE TABLE error_report_ai_analyses (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  error_report_id BIGINT UNSIGNED NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  estimated_cause VARCHAR(2000) NULL,
  fix_proposal VARCHAR(2000) NULL,
  impact_assessment VARCHAR(1000) NULL,
  suggested_files VARCHAR(1000) NULL,
  raw_response TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
  error_message VARCHAR(500) NULL,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_eraa_error_report_id FOREIGN KEY (error_report_id) REFERENCES error_reports(id) ON DELETE CASCADE,
  CONSTRAINT fk_eraa_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_eraa_error_report_id_created ON error_report_ai_analyses(error_report_id, created_at DESC);
CREATE INDEX idx_eraa_status_created ON error_report_ai_analyses(status, created_at);
