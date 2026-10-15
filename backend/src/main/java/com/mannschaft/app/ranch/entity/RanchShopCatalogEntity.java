package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** SKUの公開済み価格版。過去purchaseの版を編集しない。 */
@Entity
@Table(name = "ranch_shop_items")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchShopCatalogEntity extends RanchEntity {
    @Column(name = "sku_key", nullable = false, length = 80)
    private String skuKey;
    @Column(name = "collectible_key", nullable = false, length = 80)
    private String collectibleKey;
    @Column(name = "price_points", nullable = false)
    private long pricePoints;
    @Column(name = "price_version", nullable = false)
    private long priceVersion;
    @Column(name = "is_active", nullable = false)
    private boolean active;
}
