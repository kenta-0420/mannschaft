-- F10.6 Phase 10-δ: error_reports に SLA 対応期限カラムを追加
ALTER TABLE error_reports
  ADD COLUMN sla_due_at DATETIME NULL
    COMMENT 'SLA対応期限。CRITICAL=1h, HIGH=24h, MEDIUM=1w, LOW=NULL'
    AFTER last_ai_analysis_at;

CREATE INDEX idx_error_reports_sla_due_at
  ON error_reports (sla_due_at, status);
