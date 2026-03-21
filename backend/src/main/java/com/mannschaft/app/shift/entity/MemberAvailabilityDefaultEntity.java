package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.ShiftPreference;
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

import java.time.LocalTime;

/**
 * メンバーデフォルト勤務可能時間エンティティ。曜日ごとの勤務可能デフォルトを管理する。
 */
@Entity
@Table(name = "member_availability_defaults")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberAvailabilityDefaultEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Integer dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShiftPreference preference;

    @Column(length = 200)
    private String note;

    /**
     * 勤務可能設定を更新する。
     *
     * @param preference 希望種別
     * @param note       備考
     */
    public void updateAvailability(ShiftPreference preference, String note) {
        this.preference = preference;
        this.note = note;
    }
}
