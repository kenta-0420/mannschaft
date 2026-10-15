-- F09.2 プロモーション配信: セグメント条件テーブル
CREATE TABLE promotion_segments (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    promotion_id        BIGINT UNSIGNED     NOT NULL,
    segment_type        VARCHAR(30)         NOT NULL,
    segment_value       JSON                NOT NULL,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_ps_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    INDEX idx_ps_promo (promotion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プロモーションセグメント';
