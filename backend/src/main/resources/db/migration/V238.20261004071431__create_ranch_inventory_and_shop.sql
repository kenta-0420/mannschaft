-- 承認済み置物、元活動由来の本人所有、価格版を別々に保持する。
-- 初期catalog/商品seedは置かず、運営承認前はshop OFFを維持する。
CREATE TABLE ranch_collectible_catalog (
    collectible_key VARCHAR(80) NOT NULL,
    label_key VARCHAR(120) NOT NULL,
    asset_key VARCHAR(160) NOT NULL,
    source_kind VARCHAR(30) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (collectible_key),
    CONSTRAINT chk_ranch_collectible_catalog_source CHECK (source_kind IN ('SHOP','LEGACY_BADGE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_collectible_inventory (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    collectible_key VARCHAR(80) NOT NULL,
    acquisition_kind VARCHAR(20) NOT NULL,
    acquisition_key VARBINARY(160) NOT NULL,
    legacy_badge_id VARCHAR(80) NULL,
    legacy_award_period VARCHAR(40) NULL,
    sku_key VARCHAR(80) NULL,
    price_version BIGINT NULL,
    awarded_at DATETIME(6) NOT NULL,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_inventory_owner_id_id (owner_id,id),
    UNIQUE KEY uq_ranch_inventory_acquisition (user_id,acquisition_kind,acquisition_key),
    KEY idx_ranch_inventory_owner_revoked (owner_id,is_revoked),
    KEY idx_ranch_inventory_user_awarded_id (user_id,awarded_at,id),
    CONSTRAINT fk_ranch_inventory_owner FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT fk_ranch_inventory_collectible FOREIGN KEY (collectible_key)
        REFERENCES ranch_collectible_catalog(collectible_key),
    CONSTRAINT chk_ranch_inventory_identity CHECK (
        (acquisition_kind='SHOP' AND sku_key IS NOT NULL AND price_version IS NOT NULL
            AND price_version > 0 AND legacy_badge_id IS NULL AND legacy_award_period IS NULL)
        OR (acquisition_kind='LEGACY_BADGE' AND legacy_badge_id IS NOT NULL
            AND legacy_award_period IS NOT NULL AND sku_key IS NULL AND price_version IS NULL)),
    CONSTRAINT chk_ranch_inventory_acquisition_length CHECK (OCTET_LENGTH(acquisition_key) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_shop_items (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    sku_key VARCHAR(80) NOT NULL,
    price_version BIGINT NOT NULL,
    collectible_key VARCHAR(80) NOT NULL,
    price_points BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_shop_item_price (sku_key,price_version),
    KEY idx_ranch_shop_item_active (is_active,sku_key,price_version),
    CONSTRAINT fk_ranch_shop_item_collectible FOREIGN KEY (collectible_key)
        REFERENCES ranch_collectible_catalog(collectible_key),
    CONSTRAINT chk_ranch_shop_item_positive CHECK (price_version > 0 AND price_points > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ranch_room_placements
    ADD CONSTRAINT fk_ranch_room_placements_inventory_owner
    FOREIGN KEY (owner_id,inventory_id) REFERENCES ranch_collectible_inventory(owner_id,id);
