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

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 施設利用ルールエンティティ。施設ごとの予約制約を管理する。
 */
@Entity
@Table(name = "facility_usage_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityUsageRuleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long facilityId;

    @Column(nullable = false, precision = 3, scale = 1)
    @Builder.Default
    private BigDecimal maxHoursPerBooking = new BigDecimal("4.0");

    @Column(nullable = false, precision = 3, scale = 1)
    @Builder.Default
    private BigDecimal minHoursPerBooking = new BigDecimal("0.5");

    @Column(nullable = false)
    @Builder.Default
    private Integer maxBookingsPerMonthPerUser = 4;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxConsecutiveSlots = 8;

    @Column(nullable = false)
    @Builder.Default
    private Integer minAdvanceHours = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxAdvanceDays = 30;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxStayNights = 0;

    private Integer cancellationDeadlineHours;

    @Column(nullable = false)
    @Builder.Default
    private LocalTime availableTimeFrom = LocalTime.of(9, 0);

    @Column(nullable = false)
    @Builder.Default
    private LocalTime availableTimeTo = LocalTime.of(22, 0);

    @Column(columnDefinition = "JSON", nullable = false)
    @Builder.Default
    private String availableDaysOfWeek = "[0,1,2,3,4,5,6]";

    @Column(columnDefinition = "JSON")
    private String blackoutDates;

    @Column(length = 500)
    private String notes;

    /**
     * ルールを更新する。
     */
    public void update(BigDecimal maxHoursPerBooking, BigDecimal minHoursPerBooking,
                       Integer maxBookingsPerMonthPerUser, Integer maxConsecutiveSlots,
                       Integer minAdvanceHours, Integer maxAdvanceDays, Integer maxStayNights,
                       Integer cancellationDeadlineHours, LocalTime availableTimeFrom,
                       LocalTime availableTimeTo, String availableDaysOfWeek,
                       String blackoutDates, String notes) {
        this.maxHoursPerBooking = maxHoursPerBooking;
        this.minHoursPerBooking = minHoursPerBooking;
        this.maxBookingsPerMonthPerUser = maxBookingsPerMonthPerUser;
        this.maxConsecutiveSlots = maxConsecutiveSlots;
        this.minAdvanceHours = minAdvanceHours;
        this.maxAdvanceDays = maxAdvanceDays;
        this.maxStayNights = maxStayNights;
        this.cancellationDeadlineHours = cancellationDeadlineHours;
        this.availableTimeFrom = availableTimeFrom;
        this.availableTimeTo = availableTimeTo;
        this.availableDaysOfWeek = availableDaysOfWeek;
        this.blackoutDates = blackoutDates;
        this.notes = notes;
    }
}
