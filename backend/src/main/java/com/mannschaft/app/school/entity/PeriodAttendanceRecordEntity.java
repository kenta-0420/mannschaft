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

/** 時限別出欠（教科担任の出欠登録）。1日の各時限ごとに1生徒1レコード。 */
@Entity
@Table(name = "period_attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PeriodAttendanceRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long studentUserId;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    /** 時限番号（1〜15） */
    @Column(nullable = false)
    private Integer periodNumber;

    /** FK → timetable_slots.id */
    private Long timetableSlotId;

    /** FK → timetable_changes.id（臨時変更時） */
    private Long timetableChangeId;

    /** 教科名スナップショット（時間割変更後も履歴維持） */
    @Column(nullable = false, length = 100)
    private String subjectName;

    /** 教科担任名スナップショット */
    @Column(length = 100)
    private String teacherName;

    /** FK → users.id（教科担任） */
    private Long teacherUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AttendanceStatus status = AttendanceStatus.UNDECIDED;

    /** 遅刻分数（PARTIAL 時） */
    private Integer lateMinutes;

    @Column(length = 500)
    private String comment;

    @Column(nullable = false)
    private Long recordedBy;

    private LocalDateTime recordedAt;
    private LocalDateTime updatedAt;

    /** 「前にいたのに今いない」検知通知送信日時 */
    private LocalDateTime transitionAlertSentAt;

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
