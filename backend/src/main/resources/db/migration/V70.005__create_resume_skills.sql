-- F01.10: マイページ履歴書・職務経歴書 — resume_skills テーブル作成（活かせるスキル）
-- 設計書: docs/features/F01.10_mypage_resume.md §4.6
CREATE TABLE resume_skills (
  id            BINARY(16)   NOT NULL,
  resume_id     BINARY(16)   NOT NULL,
  skill_name    VARCHAR(100) NOT NULL,
  level         ENUM('BEGINNER','INTERMEDIATE','ADVANCED','EXPERT') NULL,
  description   VARCHAR(500) NULL,
  display_order INT          NOT NULL DEFAULT 0,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at    DATETIME     NULL,
  PRIMARY KEY (id),
  INDEX idx_resume_skills_resume_id (resume_id, display_order),
  CONSTRAINT fk_resume_skills_resume
    FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
