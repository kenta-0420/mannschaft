-- F01.9 年齢確認・保護者同意機能: 保護者同意リンクテーブルを作成
-- 未成年ユーザーの保護者同意フローを管理する
CREATE TABLE parental_consent_links (
  id              BINARY(16)      NOT NULL,
  child_user_id   BIGINT UNSIGNED NOT NULL,
  parent_user_id  BIGINT UNSIGNED NULL,
  parent_email    VARCHAR(255)    NOT NULL,
  token_hash      VARCHAR(64)     NOT NULL,
  status          ENUM('PENDING','APPROVED','REJECTED','REVOKED') NOT NULL DEFAULT 'PENDING',
  expires_at      DATETIME        NOT NULL,
  approved_at     DATETIME        NULL,
  rejected_at     DATETIME        NULL,
  revoked_at      DATETIME        NULL,
  revoked_by      BIGINT UNSIGNED NULL,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_pcl_token_hash (token_hash),
  INDEX idx_pcl_child_user_id (child_user_id),
  INDEX idx_pcl_parent_user_id (parent_user_id),
  INDEX idx_pcl_status_expires_at (status, expires_at),
  INDEX idx_pcl_child_status (child_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
