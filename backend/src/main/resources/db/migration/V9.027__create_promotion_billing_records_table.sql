-- F09.2 プロモーション配信: 課金記録テーブル
CREATE TABLE promotion_billing_records (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    promotion_id        BIGINT UNSIGNED     NOT NULL,
    scope_type          VARCHAR(20)         NOT NULL,
    scope_id            BIGINT UNSIGNED     NOT NULL,
    delivery_count      INT UNSIGNED        NOT NULL,
    unit_price          DECIMAL(6,2)        NOT NULL,
    total_amount        DECIMAL(10,0)       NOT NULL,
    billing_status      VARCHAR(20)         NOT NULL DEFAULT 'PENDING',
    stripe_charge_id    VARCHAR(100),
    billed_at           DATETIME,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_pbr_promotion
        FOREIGN KEY (promotion_id) REFERENCES promotions (id) ON DELETE CASCADE,
    INDEX idx_pbr_promo (promotion_id),
    INDEX idx_pbr_scope (scope_type, scope_id, billing_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プロモーション課金記録';
