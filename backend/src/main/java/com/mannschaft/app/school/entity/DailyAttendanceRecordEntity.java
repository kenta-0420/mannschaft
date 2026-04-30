package com.mannschaft.app.school.entity;

import com.mannschaft.app.schedule.AttendanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 日次出欠（朝の点呼記録）。1日1生徒につき1レコード。 */
@Entity
@Table(name = "daily_attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DailyAttendanceRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long studentUserId;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AttendanceStatus status = AttendanceStatus.UNDECIDED;

    @Enumerated(EnumType.STRING)
    @Column(length = 25)
    private AbsenceReason absenceReason;

    /** 実際の登校時刻（PARTIAL=遅刻の場合に記録） */
    private LocalTime arrivalTime;

    /** 早退時刻（PARTIAL=早退の場合に記録） */
    private LocalTime leaveTime;

    @Column(length = 500)
    private String comment;

    /** FK → family_attendance_notices.id */
    private Long familyNoticeId;

    @Column(nullable = false)
    private Long recordedBy;

    private LocalDateTime recordedAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.recordedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
