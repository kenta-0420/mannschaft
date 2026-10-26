-- F08.7: テンプレートのタイブレークルール
CREATE TABLE tournament_template_tiebreakers (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id BIGINT UNSIGNED NOT NULL,
    priority    TINYINT UNSIGNED NOT NULL,
    criteria    ENUM('POINTS','SCORE_DIFFERENCE','SCORE_FOR','HEAD_TO_HEAD_POINTS','HEAD_TO_HEAD_SCORE_DIFFERENCE','WINS','SET_RATIO','POINT_RATIO','LOSSES','DRAWS') NOT NULL,
    direction   ENUM('DESC','ASC') NOT NULL DEFAULT 'DESC',
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tt_tb (template_id, priority),
    CONSTRAINT fk_tt_tb_template FOREIGN KEY (template_id) REFERENCES tournament_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
