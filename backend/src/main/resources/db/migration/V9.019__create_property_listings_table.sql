-- F09.1 住民台帳: 物件掲示板テーブル
CREATE TABLE property_listings (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    dwelling_unit_id    BIGINT UNSIGNED     NOT NULL,
    listed_by           BIGINT UNSIGNED     NOT NULL,
    listing_type        VARCHAR(10)         NOT NULL,
    title               VARCHAR(200)        NOT NULL,
    description         TEXT,
    asking_price        DECIMAL(12,0),
    monthly_rent        DECIMAL(10,0),
    status              VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',
    expires_at          DATETIME,
    image_urls          JSON,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,

    PRIMARY KEY (id),
    CONSTRAINT fk_pl_dwelling_unit
        FOREIGN KEY (dwelling_unit_id) REFERENCES dwelling_units (id) ON DELETE CASCADE,
    CONSTRAINT fk_pl_listed_by
        FOREIGN KEY (listed_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_pl_unit (dwelling_unit_id),
    INDEX idx_pl_scope_status (status, listing_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物件掲示板';
