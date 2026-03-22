package com.mannschaft.app.facility.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 施設予約日次統計エンティティ。
 */
@Entity
@Table(name = "facility_booking_daily_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityBookingDailyStatsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long facilityId;

    @Column(nullable = false)
    private LocalDate statDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer bookingCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer completedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer noShowCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer cancelledCount = 0;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal revenueTotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal revenueStripe = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal revenueDirect = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal platformFeeTotal = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Integer slotCountBooked = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer slotCountAvailable = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer stayNightsTotal = 0;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
