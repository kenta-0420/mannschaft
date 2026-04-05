package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * シフト枠エンティティ。特定日時のシフト枠を管理する。
 */
@Entity
@Table(name = "shift_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private LocalDate slotDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private Long positionId;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer requiredCount = 1;

    @Column(columnDefinition = "JSON")
    private String assignedUserIds;

    @Column(length = 200)
    private String note;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
