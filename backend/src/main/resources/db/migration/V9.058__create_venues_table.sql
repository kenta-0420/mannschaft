-- 施設マスタテーブル（Google Places APIで正規化された場所情報を保持）
CREATE TABLE venues (
    id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    google_place_id   VARCHAR(300)     NULL     COMMENT 'Google Places place_id（手動登録時はNULL）',
    name              VARCHAR(200)     NOT NULL COMMENT '施設名',
    address           VARCHAR(500)     NULL     COMMENT '住所',
    latitude          DECIMAL(10, 7)   NULL     COMMENT '緯度',
    longitude         DECIMAL(10, 7)   NULL     COMMENT '経度',
    prefecture        VARCHAR(20)      NULL     COMMENT '都道府県',
    city              VARCHAR(50)      NULL     COMMENT '市区町村',
    category          VARCHAR(50)      NULL     COMMENT '施設カテゴリ（park / stadium / gym 等）',
    phone_number      VARCHAR(30)      NULL     COMMENT '電話番号',
    website_url       VARCHAR(500)     NULL     COMMENT 'WebサイトURL',
    usage_count       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '利用回数（キャッシュ優先度用）',
    created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_venues_google_place_id (google_place_id),
    INDEX idx_venues_prefecture_city (prefecture, city),
    INDEX idx_venues_category (category),
    INDEX idx_venues_name (name),
    INDEX idx_venues_usage_count (usage_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='施設マスタ（Google Places正規化）';
