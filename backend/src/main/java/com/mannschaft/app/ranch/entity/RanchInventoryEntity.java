package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/** 本人が永久所有する置物。配置変更では行を消さない。 */
@Entity
@Table(name = "ranch_collectible_inventory")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchInventoryEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "sku_key", length = 80)
    private String skuKey;
    @Column(name = "collectible_key", nullable = false, length = 80)
    private String collectibleKey;
    @Column(name = "acquisition_kind", nullable = false, length = 20)
    private String acquisitionKind;
    @Column(name = "acquisition_key", nullable = false, length = 160)
    private byte[] acquisitionKey;
    @Column(name = "legacy_badge_id", length = 80)
    private String legacyBadgeId;
    @Column(name = "legacy_award_period", length = 40)
    private String legacyAwardPeriod;
    @Column(name = "price_version")
    private Long priceVersion;
    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt;
    @Column(name = "is_revoked", nullable = false)
    private boolean revoked;

    public void revoke() {
        revoked = true;
    }
}
