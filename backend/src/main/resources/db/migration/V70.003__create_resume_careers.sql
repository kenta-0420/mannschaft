-- F01.10: マイページ履歴書・職務経歴書 — resume_careers テーブル作成（職歴）
-- 設計書: docs/features/F01.10_mypage_resume.md §4.4
CREATE TABLE resume_careers (
  id                        BINARY(16)   NOT NULL,
  resume_id                 BINARY(16)   NOT NULL,
  entry_year                SMALLINT     NOT NULL,
  entry_month               TINYINT      NULL,
  end_year                  SMALLINT     NULL,
  end_month                 TINYINT      NULL,
  is_current                BOOLEAN      NOT NULL DEFAULT FALSE,
  company_name              VARCHAR(255) NOT NULL,
  department                VARCHAR(255) NULL,
  employment_type           VARCHAR(50)  NULL,
  business_summary          VARCHAR(500) NULL,
  job_description           TEXT         NULL,
  achievements              TEXT         NULL,
  include_in_rirekisho      BOOLEAN      NOT NULL DEFAULT TRUE,
  include_in_shokumukeireki BOOLEAN      NOT NULL DEFAULT TRUE,
  display_order             INT          NOT NULL DEFAULT 0,
  created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at                DATETIME     NULL,
  PRIMARY KEY (id),
  INDEX idx_resume_careers_resume_id (resume_id, display_order),
  CONSTRAINT fk_resume_careers_resume
    FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
