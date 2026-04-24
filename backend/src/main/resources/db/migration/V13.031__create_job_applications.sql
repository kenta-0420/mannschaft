-- F13.1 Phase 13.1.1 MVP: 求人応募テーブル
-- 論理削除なし（status で管理）。採用は ACCEPTED、辞退は WITHDRAWN、不採用は REJECTED。
CREATE TABLE job_applications (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_posting_id BIGINT UNSIGNED NOT NULL,
  applicant_user_id BIGINT UNSIGNED NOT NULL,
  self_pr TEXT NULL,
  status ENUM('APPLIED','ACCEPTED','REJECTED','WITHDRAWN') NOT NULL DEFAULT 'APPLIED',
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME NULL,
  decided_by_user_id BIGINT UNSIGNED NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ja_posting FOREIGN KEY (job_posting_id) REFERENCES job_postings (id) ON DELETE CASCADE,
  CONSTRAINT fk_ja_applicant FOREIGN KEY (applicant_user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ja_decider FOREIGN KEY (decided_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
  CONSTRAINT uk_ja_posting_applicant UNIQUE (job_posting_id, applicant_user_id),
  CONSTRAINT chk_self_pr_length CHECK (CHAR_LENGTH(self_pr) <= 500),
  INDEX idx_ja_posting_status (job_posting_id, status),
  INDEX idx_ja_applicant_status (applicant_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F13.1 MVP: 求人応募';
