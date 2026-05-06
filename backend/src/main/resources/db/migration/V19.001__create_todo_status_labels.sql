-- F02.3.1 Phase 1a: TODO カスタムステータスラベル
-- スコープ別（SYSTEM/PERSONAL/TEAM/ORGANIZATION）にラベルを定義し、
-- 3バケット（OPEN/IN_PROGRESS/COMPLETED）にマッピングする。
-- name 重複防止は Service 層で対応（MySQL の partial unique 不可のため）。
CREATE TABLE todo_status_labels (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type        VARCHAR(20) NOT NULL,
  scope_id          BIGINT NULL,
  name              VARCHAR(50) NOT NULL,
  bucket            VARCHAR(20) NOT NULL,
  color             VARCHAR(7) NULL,
  sort_order        INT NOT NULL DEFAULT 0,
  is_system_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_by        BIGINT NULL,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at        DATETIME NULL,
  CONSTRAINT chk_tsl_bucket CHECK (bucket IN ('OPEN','IN_PROGRESS','COMPLETED')),
  CONSTRAINT chk_tsl_scope CHECK (scope_type IN ('SYSTEM','PERSONAL','TEAM','ORGANIZATION')),
  INDEX idx_tsl_scope (scope_type, scope_id, sort_order),
  INDEX idx_tsl_bucket (bucket)
);
