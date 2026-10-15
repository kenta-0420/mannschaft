CREATE TABLE shift_assignments (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  slot_id       BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  run_id        BIGINT UNSIGNED NULL COMMENT 'NULL=手動割当',
  status        ENUM('PROPOSED','CONFIRMED','REVOKED') NOT NULL DEFAULT 'PROPOSED',
  score         DECIMAL(8,4) NULL COMMENT '貪欲法スコア（手動時NULL）',
  assigned_by   BIGINT UNSIGNED NOT NULL,
  note          VARCHAR(500) NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version       INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shift_assignments_slot_user_run (slot_id, user_id, run_id),
  INDEX idx_shift_assignments_slot_id (slot_id),
  INDEX idx_shift_assignments_run_id (run_id),
  INDEX idx_shift_assignments_user_id (user_id),
  CONSTRAINT fk_shift_assignments_slot FOREIGN KEY (slot_id) REFERENCES shift_slots(id) ON DELETE CASCADE,
  CONSTRAINT fk_shift_assignments_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='シフト割当（提案・確定・取消）';
