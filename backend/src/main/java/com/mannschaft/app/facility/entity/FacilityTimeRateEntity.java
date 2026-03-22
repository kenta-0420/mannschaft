package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.facility.DayType;
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

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 施設時間帯別料金エンティティ。
 */
@Entity
@Table(name = "facility_time_rates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityTimeRateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long facilityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayType dayType;

    @Column(nullable = false)
    private LocalTime timeFrom;

    @Column(nullable = false)
    private LocalTime timeTo;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal ratePerSlot;
}
