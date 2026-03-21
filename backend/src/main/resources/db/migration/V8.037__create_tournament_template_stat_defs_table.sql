-- F08.7: テンプレートの個人成績項目定義
CREATE TABLE tournament_template_stat_defs (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id       BIGINT UNSIGNED NOT NULL,
    name              VARCHAR(50)     NOT NULL,
    stat_key          VARCHAR(30)     NOT NULL,
    unit              VARCHAR(20)     NULL,
    data_type         ENUM('INTEGER','DECIMAL','TIME') NOT NULL DEFAULT 'INTEGER',
    aggregation_type  ENUM('SUM','AVG','MAX','MIN') NOT NULL DEFAULT 'SUM',
    is_ranking_target BOOLEAN         NOT NULL DEFAULT TRUE,
    ranking_label     VARCHAR(50)     NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tt_sd_key (template_id, stat_key),
    INDEX idx_tt_sd_ranking (template_id, is_ranking_target, sort_order),
    CONSTRAINT fk_tt_sd_template FOREIGN KEY (template_id) REFERENCES tournament_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
