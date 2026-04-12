CREATE TABLE equipment_ranking_exclusions (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  exclusion_type       ENUM('TEAM_OPT_OUT','ITEM_EXCLUSION') NOT NULL,
  team_id              BIGINT UNSIGNED NULL,
  normalized_name      VARCHAR(200)    NULL,
  reason               VARCHAR(300)    NULL,
  excluded_by_user_id  BIGINT UNSIGNED NULL,
  created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE INDEX uq_ere_team_opt_out (team_id, exclusion_type),
  UNIQUE INDEX uq_ere_item_name (normalized_name, exclusion_type),
  CONSTRAINT chk_ere_scope CHECK (
    (exclusion_type = 'TEAM_OPT_OUT' AND team_id IS NOT NULL AND normalized_name IS NULL)
    OR (exclusion_type = 'ITEM_EXCLUSION' AND team_id IS NULL AND normalized_name IS NOT NULL)
  ),
  CONSTRAINT fk_ere_team FOREIGN KEY (team_id) REFERENCES teams(id),
  CONSTRAINT fk_ere_user FOREIGN KEY (excluded_by_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
