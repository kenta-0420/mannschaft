-- F08.7: 大会のタイブレーク優先順位（テンプレートからコピー）
CREATE TABLE tournament_tiebreakers (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tournament_id BIGINT UNSIGNED NOT NULL,
    priority      TINYINT UNSIGNED NOT NULL,
    criteria      ENUM('POINTS','SCORE_DIFFERENCE','SCORE_FOR','HEAD_TO_HEAD_POINTS','HEAD_TO_HEAD_SCORE_DIFFERENCE','WINS','SET_RATIO','POINT_RATIO','LOSSES','DRAWS') NOT NULL,
    direction     ENUM('DESC','ASC') NOT NULL DEFAULT 'DESC',
    PRIMARY KEY (id),
    UNIQUE INDEX uq_t_tb (tournament_id, priority),
    CONSTRAINT fk_t_tb_tournament FOREIGN KEY (tournament_id) REFERENCES tournaments (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
