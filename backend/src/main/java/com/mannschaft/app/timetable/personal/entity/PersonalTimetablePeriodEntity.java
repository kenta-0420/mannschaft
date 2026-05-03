package com.mannschaft.app.timetable.personal.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * F03.15 個人時間割の時限定義。
 *
 * <p>1個人時間割あたり最大15枠（アプリ層）。組織テンプレートに依存せず個別定義する。</p>
 */
@Entity
@Table(name = "personal_timetable_periods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PersonalTimetablePeriodEntity extends BaseEntity {

    @Column(nullable = false)
    private Long personalTimetableId;

    @Column(nullable = false)
    private Integer periodNumber;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isBreak = false;
}
