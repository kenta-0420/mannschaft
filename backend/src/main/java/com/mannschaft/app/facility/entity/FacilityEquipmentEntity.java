package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 施設付帯備品エンティティ。
 */
@Entity
@Table(name = "facility_equipment")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityEquipmentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long facilityId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalQuantity = 1;

    @Column(precision = 10, scale = 0)
    private BigDecimal pricePerUse;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAvailable = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    private LocalDateTime deletedAt;

    /**
     * 備品情報を更新する。
     */
    public void update(String name, String description, Integer totalQuantity,
                       BigDecimal pricePerUse, Boolean isAvailable, Integer displayOrder) {
        this.name = name;
        this.description = description;
        this.totalQuantity = totalQuantity;
        this.pricePerUse = pricePerUse;
        this.isAvailable = isAvailable;
        this.displayOrder = displayOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
