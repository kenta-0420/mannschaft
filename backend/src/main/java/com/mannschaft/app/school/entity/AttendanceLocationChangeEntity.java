package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 登校場所変更履歴。1生徒・1日につき複数回の場所変更を記録する。 */
@Entity
@Table(name = "attendance_location_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AttendanceLocationChangeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long studentUserId;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private AttendanceLocation fromLocation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private AttendanceLocation toLocation;

    /** 変更が発生した時限番号（任意） */
    private Integer changedAtPeriod;

    /** 変更が発生した時刻（任意） */
    private LocalTime changedAtTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private AttendanceLocationChangeReason reason;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Long recordedBy;

    private LocalDateTime recordedAt;

    @PrePersist
    protected void onRecordCreate() {
        this.recordedAt = LocalDateTime.now();
    }
}
