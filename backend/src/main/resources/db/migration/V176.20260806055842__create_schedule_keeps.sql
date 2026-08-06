-- F03.17 キープ（日付未定の予定 / Schedule Keep）
-- 設計書: docs/features/F03.17_schedule_keep.md §3.3
-- rollback: DROP TABLE schedule_keeps;

CREATE TABLE schedule_keeps (
  id                    BINARY(16)      NOT NULL          COMMENT 'UUIDv7 主キー',
  team_id               BIGINT UNSIGNED NULL,
  organization_id       BIGINT UNSIGNED NULL,
  user_id               BIGINT UNSIGNED NULL,
  title                 VARCHAR(200)    NOT NULL,
  memo                  TEXT            NULL,
  candidate_dates       JSON            NULL,
  status                VARCHAR(20)     NOT NULL DEFAULT 'KEPT',
  converted_schedule_id BIGINT UNSIGNED NULL,
  sort_order            INT UNSIGNED    NOT NULL DEFAULT 0,
  created_by            BIGINT UNSIGNED NULL,
  created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at            DATETIME        NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_schedule_keeps_scope_xor CHECK (
        (CASE WHEN team_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN organization_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN user_id IS NOT NULL THEN 1 ELSE 0 END)
      = 1
  ),
  CONSTRAINT ck_schedule_keeps_converted CHECK (
       (status = 'KEPT'      AND converted_schedule_id IS NULL)
    OR (status = 'SCHEDULED' AND converted_schedule_id IS NOT NULL)
    OR (status = 'ARCHIVED')
  ),
  INDEX idx_skeep_team      (team_id,         status, sort_order, created_at DESC),
  INDEX idx_skeep_org       (organization_id, status, sort_order, created_at DESC),
  INDEX idx_skeep_user      (user_id,         status, sort_order, created_at DESC),
  INDEX idx_skeep_conv_team (team_id,         converted_schedule_id),
  INDEX idx_skeep_conv_org  (organization_id, converted_schedule_id),
  INDEX idx_skeep_conv_user (user_id,         converted_schedule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='キープ（日付未定の予定）。F03.17';
