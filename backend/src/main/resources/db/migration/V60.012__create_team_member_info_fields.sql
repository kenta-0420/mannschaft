CREATE TABLE team_member_info_fields (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id                  BIGINT UNSIGNED NOT NULL,
  field_name               VARCHAR(100)    NOT NULL,
  field_type               ENUM('TEXT','PHONE','EMAIL','DATE') NOT NULL DEFAULT 'TEXT',
  is_required              TINYINT(1)      NOT NULL DEFAULT 0,
  is_sensitive             TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '1=AES-256-GCM暗号化保存',
  refresh_interval_months  TINYINT UNSIGNED NULL COMMENT 'NULL=無期限 / 12,36,60 のみ許可',
  sort_order               INT             NOT NULL DEFAULT 0,
  is_active                TINYINT(1)      NOT NULL DEFAULT 1,
  created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tmif_team (team_id),
  CONSTRAINT fk_tmif_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT chk_tmif_interval CHECK (
    refresh_interval_months IS NULL
    OR refresh_interval_months IN (12, 36, 60)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
