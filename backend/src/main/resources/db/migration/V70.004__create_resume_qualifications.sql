-- F01.10: マイページ履歴書・職務経歴書 — resume_qualifications テーブル作成（免許・資格）
-- 設計書: docs/features/F01.10_mypage_resume.md §4.5
CREATE TABLE resume_qualifications (
  id             BINARY(16)   NOT NULL,
  resume_id      BINARY(16)   NOT NULL,
  acquired_year  SMALLINT     NOT NULL,
  acquired_month TINYINT      NULL,
  name           VARCHAR(255) NOT NULL,
  note           VARCHAR(255) NULL,
  display_order  INT          NOT NULL DEFAULT 0,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at     DATETIME     NULL,
  PRIMARY KEY (id),
  INDEX idx_resume_qualifications_resume_id (resume_id, display_order),
  CONSTRAINT fk_resume_qualifications_resume
    FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
