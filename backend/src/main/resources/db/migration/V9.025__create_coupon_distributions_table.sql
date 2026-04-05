-- F09.2 プロモーション配信: クーポン配布テーブル
CREATE TABLE coupon_distributions (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    coupon_id           BIGINT UNSIGNED     NOT NULL,
    user_id             BIGINT UNSIGNED     NOT NULL,
    promotion_id        BIGINT UNSIGNED,
    status              VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    distributed_at      DATETIME            NOT NULL,
    expires_at          DATETIME            NOT NULL,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_cd_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupons (id) ON DELETE CASCADE,
    CONSTRAINT fk_cd_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cd_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE SET NULL,
    INDEX idx_cd_coupon_user (coupon_id, user_id),
    INDEX idx_cd_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='クーポン配布';
