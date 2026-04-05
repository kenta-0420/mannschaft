-- F08.7: プリセットの個人成績項目定義
CREATE TABLE system_tournament_preset_stat_defs (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    preset_id         BIGINT UNSIGNED NOT NULL,
    name              VARCHAR(50)     NOT NULL,
    stat_key          VARCHAR(30)     NOT NULL,
    unit              VARCHAR(20)     NULL,
    data_type         ENUM('INTEGER','DECIMAL','TIME') NOT NULL DEFAULT 'INTEGER',
    aggregation_type  ENUM('SUM','AVG','MAX','MIN') NOT NULL DEFAULT 'SUM',
    is_ranking_target BOOLEAN         NOT NULL DEFAULT TRUE,
    ranking_label     VARCHAR(50)     NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_stp_sd_key (preset_id, stat_key),
    INDEX idx_stp_sd_ranking (preset_id, is_ranking_target, sort_order),
    CONSTRAINT fk_stp_sd_preset FOREIGN KEY (preset_id) REFERENCES system_tournament_presets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
