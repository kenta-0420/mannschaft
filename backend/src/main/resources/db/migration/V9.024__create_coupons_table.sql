-- F09.2 プロモーション配信: クーポンテーブル
CREATE TABLE coupons (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    scope_type          ENUM('TEAM','ORGANIZATION') NOT NULL,
    scope_id            BIGINT UNSIGNED     NOT NULL,
    created_by          BIGINT UNSIGNED     NOT NULL,
    title               VARCHAR(200)        NOT NULL,
    description         TEXT,
    coupon_type         VARCHAR(20)         NOT NULL,
    discount_value      DECIMAL(10,2),
    min_purchase_amount DECIMAL(10,0),
    max_issues          INT UNSIGNED,
    issued_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    max_uses_per_user   SMALLINT UNSIGNED   NOT NULL DEFAULT 1,
    valid_from          DATETIME            NOT NULL,
    valid_until         DATETIME            NOT NULL,
    is_active           BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,

    PRIMARY KEY (id),
    CONSTRAINT fk_coupons_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_coupons_scope (scope_type, scope_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='クーポン';
