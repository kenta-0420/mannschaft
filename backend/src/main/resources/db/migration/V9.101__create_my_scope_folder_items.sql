CREATE TABLE my_scope_folder_items (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  folder_id  BIGINT NOT NULL,
  scope_id   BIGINT NOT NULL COMMENT 'team_id or organization_id',
  sort_order INT    NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_msfi_folder FOREIGN KEY (folder_id) REFERENCES my_scope_folders(id) ON DELETE CASCADE,
  UNIQUE KEY uq_msfi_folder_scope (folder_id, scope_id),
  INDEX idx_msfi_folder (folder_id)
);
