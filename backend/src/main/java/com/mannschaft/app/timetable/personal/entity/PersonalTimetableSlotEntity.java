package com.mannschaft.app.timetable.personal.entity;

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

import java.math.BigDecimal;

/**
 * F03.15 個人時間割のコマ。
 *
 * <p>linked_team_id / linked_timetable_id / linked_slot_id によるチームリンクは Phase 4 で実装。
 * Phase 1 ではテーブル列のみ存在し、Service ロジックでは未使用。</p>
 */
@Entity
@Table(name = "personal_timetable_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PersonalTimetableSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long personalTimetableId;

    @Column(nullable = false, length = 3)
    private String dayOfWeek;

    @Column(nullable = false)
    private Integer periodNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    @Builder.Default
    private WeekPattern weekPattern = WeekPattern.EVERY;

    @Column(nullable = false, length = 200)
    private String subjectName;

    @Column(length = 50)
    private String courseCode;

    @Column(length = 100)
    private String teacherName;

    @Column(length = 200)
    private String roomName;

    @Column(precision = 3, scale = 1)
    private BigDecimal credits;

    @Column(length = 7)
    private String color;

    private Long linkedTeamId;

    private Long linkedTimetableId;

    private Long linkedSlotId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoSyncChanges = true;

    @Column(length = 300)
    private String notes;
}
