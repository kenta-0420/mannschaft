-- F09.2 プロモーション配信: 配信サマリーテーブル
CREATE TABLE promotion_delivery_summaries (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    promotion_id        BIGINT UNSIGNED     NOT NULL,
    summary_date        DATE                NOT NULL,
    delivered_count     INT UNSIGNED        NOT NULL DEFAULT 0,
    opened_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    failed_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pds_promo_date (promotion_id, summary_date),
    CONSTRAINT fk_pds_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プロモーション配信サマリー';
