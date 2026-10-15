CREATE TABLE equipment_rankings (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_template       VARCHAR(50)     NOT NULL,
  category            VARCHAR(100)    NOT NULL DEFAULT '__ALL__',
  `rank`              SMALLINT UNSIGNED NOT NULL,
  item_name           VARCHAR(200)    NOT NULL,
  normalized_name     VARCHAR(200)    NOT NULL,
  amazon_asin         VARCHAR(10)     NULL,
  asin_confidence     TINYINT UNSIGNED NULL,
  team_count          INT UNSIGNED    NOT NULL,
  total_quantity      INT UNSIGNED    NOT NULL,
  consume_event_count INT UNSIGNED    NOT NULL DEFAULT 0,
  score               DECIMAL(10,2)   NOT NULL,
  calculated_at       DATETIME        NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_er_template_category_rank (team_template, category, `rank`),
  INDEX idx_er_template_rank (team_template, `rank`),
  UNIQUE INDEX uq_er_template_category_name (team_template, category, normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
