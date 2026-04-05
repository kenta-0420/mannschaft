package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.facility.FacilityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 共用施設エンティティ。施設マスタ情報を管理する。
 */
@Entity
@Table(name = "shared_facilities")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SharedFacilityEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacilityType facilityType;

    @Column(length = 50)
    private String facilityTypeLabel;

    @Column(nullable = false)
    private Integer capacity;

    @Column(length = 10)
    private String floor;

    @Column(length = 200)
    private String locationDetail;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "JSON")
    private String imageUrls;

    @Column(precision = 10, scale = 0)
    private BigDecimal ratePerSlot;

    @Column(precision = 10, scale = 0)
    private BigDecimal ratePerNight;

    private LocalTime checkInTime;

    private LocalTime checkOutTime;

    @Column(nullable = false)
    @Builder.Default
    private Integer cleaningBufferMinutes = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoApprove = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 施設情報を更新する。
     */
    public void update(String name, FacilityType facilityType, String facilityTypeLabel,
                       Integer capacity, String floor, String locationDetail,
                       String description, String imageUrls, BigDecimal ratePerSlot,
                       BigDecimal ratePerNight, LocalTime checkInTime, LocalTime checkOutTime,
                       Integer cleaningBufferMinutes, Boolean autoApprove, Boolean isActive,
                       Integer displayOrder) {
        this.name = name;
        this.facilityType = facilityType;
        this.facilityTypeLabel = facilityTypeLabel;
        this.capacity = capacity;
        this.floor = floor;
        this.locationDetail = locationDetail;
        this.description = description;
        this.imageUrls = imageUrls;
        this.ratePerSlot = ratePerSlot;
        this.ratePerNight = ratePerNight;
        this.checkInTime = checkInTime;
        this.checkOutTime = checkOutTime;
        this.cleaningBufferMinutes = cleaningBufferMinutes;
        this.autoApprove = autoApprove;
        this.isActive = isActive;
        this.displayOrder = displayOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
