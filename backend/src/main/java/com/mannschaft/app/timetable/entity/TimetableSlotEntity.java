package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.WeekPattern;
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

/**
 * 時間割スロットエンティティ。各曜日・時限の授業情報を保持する。
 */
@Entity
@Table(name = "timetable_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long timetableId;

    @Column(nullable = false, length = 3)
    private String dayOfWeek;

    @Column(nullable = false)
    private Integer periodNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WeekPattern weekPattern = WeekPattern.EVERY;

    @Column(nullable = false, length = 100)
    private String subjectName;

    @Column(length = 100)
    private String teacherName;

    @Column(length = 100)
    private String roomName;

    @Column(length = 7)
    private String color;

    @Column(length = 300)
    private String notes;
}
