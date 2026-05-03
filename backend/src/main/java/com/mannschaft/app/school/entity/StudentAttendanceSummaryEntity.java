package com.mannschaft.app.school.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 学期/年度別出席集計エンティティ。生徒ごとの出席状況を集計して保持する。 */
@Entity
@Table(name = "student_attendance_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class StudentAttendanceSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** チームID */
    @Column(nullable = false)
    private Long teamId;

    /** 生徒ユーザーID */
    @Column(nullable = false)
    private Long studentUserId;

    /** 対象学期ID（NULLなら年度通算） */
    private Long termId;

    /** 学年度（例: 2025） */
    @Column(nullable = false)
    private Short academicYear;

    /** 集計期間開始（転入生対応） */
    @Column(nullable = false)
    private LocalDate periodFrom;

    /** 集計期間終了 */
    @Column(nullable = false)
    private LocalDate periodTo;

    /** 授業日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short totalSchoolDays = 0;

    /** 出席日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short presentDays = 0;

    /** 欠席日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short absentDays = 0;

    /** 遅刻回数 */
    @Column(nullable = false)
    @Builder.Default
    private Short lateCount = 0;

    /** 早退回数 */
    @Column(nullable = false)
    @Builder.Default
    private Short earlyLeaveCount = 0;

    /** 公欠日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short officialAbsenceDays = 0;

    /** 学校行事日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short schoolActivityDays = 0;

    /** 保健室登校日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short sickBayDays = 0;

    /** 別室登校日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short separateRoomDays = 0;

    /** オンライン登校日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short onlineDays = 0;

    /** 家庭学習日数 */
    @Column(nullable = false)
    @Builder.Default
    private Short homeLearningDays = 0;

    /** 出席率（%） */
    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal attendanceRate = BigDecimal.ZERO;

    /** 総時限数 */
    @Column(nullable = false)
    @Builder.Default
    private Short totalPeriods = 0;

    /** 出席時限数 */
    @Column(nullable = false)
    @Builder.Default
    private Short presentPeriods = 0;

    /** 時限別出席率（%） */
    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal periodAttendanceRate = BigDecimal.ZERO;

    /** 教科別出席（JSON文字列）例: {math:{present:40,absent:2,rate:95.2},...} */
    @Column(columnDefinition = "JSON")
    private String subjectBreakdown;

    /** 最終再集計日時 */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime lastRecalculatedAt = LocalDateTime.now();

    /** 作成日時 */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 更新日時 */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
