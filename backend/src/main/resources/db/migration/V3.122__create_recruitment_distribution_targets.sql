-- F03.11 募集型予約: 配信対象テーブル (Phase 2)
-- 募集ごとの新着配信スコープ設定
CREATE TABLE recruitment_distribution_targets (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    listing_id  BIGINT UNSIGNED NOT NULL,
    target_type ENUM('MEMBERS','SUPPORTERS','FOLLOWERS','PUBLIC_FEED') NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rdt_listing_type (listing_id, target_type),
    CONSTRAINT fk_rdt_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
