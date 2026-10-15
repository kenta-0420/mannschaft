-- F08.7: プリセットのタイブレーク優先順位
CREATE TABLE system_tournament_preset_tiebreakers (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    preset_id   BIGINT UNSIGNED NOT NULL,
    priority    TINYINT UNSIGNED NOT NULL,
    criteria    ENUM('POINTS','SCORE_DIFFERENCE','SCORE_FOR','HEAD_TO_HEAD_POINTS','HEAD_TO_HEAD_SCORE_DIFFERENCE','WINS','SET_RATIO','POINT_RATIO','LOSSES','DRAWS') NOT NULL,
    direction   ENUM('DESC','ASC') NOT NULL DEFAULT 'DESC',
    PRIMARY KEY (id),
    UNIQUE INDEX uq_stp_tb (preset_id, priority),
    CONSTRAINT fk_stp_tb_preset FOREIGN KEY (preset_id) REFERENCES system_tournament_presets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
