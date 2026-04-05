package com.mannschaft.app.parking.entity;

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
import java.time.LocalDateTime;

/**
 * 区画料金変更履歴エンティティ。
 */
@Entity
@Table(name = "parking_space_price_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSpacePriceHistoryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    @Column(precision = 10, scale = 0)
    private BigDecimal oldPrice;

    @Column(precision = 10, scale = 0)
    private BigDecimal newPrice;

    @Column(nullable = false)
    private Long changedBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();
}
