CREATE TABLE my_scope_folders (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  scope_type VARCHAR(20)  NOT NULL COMMENT 'TEAM or ORGANIZATION',
  name       VARCHAR(100) NOT NULL,
  color      VARCHAR(7)   NULL     COMMENT 'HEX color (#RRGGBB)',
  sort_order INT          NOT NULL DEFAULT 0,
  created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6)  NULL,
  CONSTRAINT fk_msf_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  INDEX idx_msf_user_scope (user_id, scope_type, deleted_at)
);
