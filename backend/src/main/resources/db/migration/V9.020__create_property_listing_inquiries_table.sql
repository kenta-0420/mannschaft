-- F09.1 住民台帳: 物件問い合わせテーブル
CREATE TABLE property_listing_inquiries (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    listing_id          BIGINT UNSIGNED     NOT NULL,
    user_id             BIGINT UNSIGNED     NOT NULL,
    message             TEXT,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pli_listing_user (listing_id, user_id),
    CONSTRAINT fk_pli_listing
        FOREIGN KEY (listing_id) REFERENCES property_listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_pli_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物件問い合わせ';
