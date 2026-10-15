-- F09.2 プロモーション配信: クーポン利用テーブル
CREATE TABLE coupon_redemptions (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    distribution_id     BIGINT UNSIGNED     NOT NULL,
    redeemed_by         BIGINT UNSIGNED     NOT NULL,
    redeemed_at         DATETIME            NOT NULL,
    redemption_detail   JSON,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_cr_distribution
        FOREIGN KEY (distribution_id) REFERENCES coupon_distributions (id) ON DELETE CASCADE,
    CONSTRAINT fk_cr_redeemed_by
        FOREIGN KEY (redeemed_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_cr_dist (distribution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='クーポン利用';
