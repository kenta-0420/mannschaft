package com.mannschaft.app.shift.entity;

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
import java.time.LocalDate;

/**
 * シフト時給設定エンティティ。メンバーの時給と適用開始日を管理する。
 */
@Entity
@Table(name = "shift_hourly_rates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftHourlyRateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /**
     * 時給を更新する。
     *
     * @param hourlyRate 新しい時給
     */
    public void changeRate(BigDecimal hourlyRate) {
        this.hourlyRate = hourlyRate;
    }
}
