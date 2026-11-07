package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 承認済み素材へのみ対応する自然キーcatalog。 */
@Entity
@Table(name = "ranch_collectible_catalog")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchCollectibleCatalogEntity {
    @Id
    @Column(name = "collectible_key", nullable = false, length = 80)
    private String collectibleKey;
    @Column(name = "label_key", nullable = false, length = 120)
    private String labelKey;
    @Column(name = "asset_key", nullable = false, length = 160)
    private String assetKey;
    @Column(name = "source_kind", nullable = false, length = 30)
    private String sourceKind;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
