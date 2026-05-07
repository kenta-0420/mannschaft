-- F12.5 Phase 2: 個別発生ログ（タイムライン表示・統計用）
-- error_reports は集約レコード、こちらは1発生 = 1行
CREATE TABLE error_report_occurrences (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  error_report_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  page_url VARCHAR(2048) NOT NULL,
  user_agent VARCHAR(500) NULL,
  ip_address VARCHAR(45) NULL,
  request_id VARCHAR(36) NULL,
  occurred_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_ero_error_report_id FOREIGN KEY (error_report_id) REFERENCES error_reports(id) ON DELETE CASCADE,
  CONSTRAINT fk_ero_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_ero_error_report_id_occurred ON error_report_occurrences(error_report_id, occurred_at DESC);
CREATE INDEX idx_ero_request_id ON error_report_occurrences(request_id);
