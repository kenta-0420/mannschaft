-- F09.2 プロモーション配信: 配信テーブル
CREATE TABLE promotion_deliveries (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    promotion_id        BIGINT UNSIGNED     NOT NULL,
    user_id             BIGINT UNSIGNED     NOT NULL,
    channel             VARCHAR(20)         NOT NULL,
    status              VARCHAR(20)         NOT NULL DEFAULT 'PENDING',
    delivered_at        DATETIME,
    opened_at           DATETIME,
    failed_reason       VARCHAR(200),
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pd_promo_user (promotion_id, user_id),
    CONSTRAINT fk_pd_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_pd_user (user_id, created_at DESC),
    INDEX idx_pd_status (promotion_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プロモーション配信';
