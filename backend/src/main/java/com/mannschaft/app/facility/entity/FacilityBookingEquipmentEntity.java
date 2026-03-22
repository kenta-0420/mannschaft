package com.mannschaft.app.facility.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 予約備品紐付けエンティティ。予約で使用する備品と数量を管理する。
 */
@Entity
@Table(name = "facility_booking_equipment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityBookingEquipmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bookingId;

    @Column(nullable = false)
    private Long equipmentId;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;
}
