package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 予約営業時間エンティティ。チームの曜日別営業時間を管理する。
 */
@Entity
@Table(name = "reservation_business_hours")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ReservationBusinessHourEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 3)
    private String dayOfWeek;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOpen = true;

    private LocalTime openTime;

    private LocalTime closeTime;

    @Column(name = "ends_next_day", nullable = false)
    @Builder.Default
    private Boolean endsNextDay = false;

    /**
     * 営業時間を更新する。
     *
     * @param isOpen    営業日かどうか
     * @param openTime  開店時刻
     * @param closeTime 閉店時刻
     */
    public void updateHours(Boolean isOpen, LocalTime openTime, LocalTime closeTime) {
        this.isOpen = isOpen;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

    public void updateHours(Boolean isOpen, LocalTime openTime, LocalTime closeTime, Boolean endsNextDay) {
        updateHours(isOpen, openTime, closeTime);
        this.endsNextDay = endsNextDay == null ? false : endsNextDay;
    }
}
