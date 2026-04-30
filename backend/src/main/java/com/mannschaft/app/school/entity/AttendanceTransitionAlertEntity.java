package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 「前にいたのに今いない」検知ログ。担任・保護者への通知起点。 */
@Entity
@Table(name = "attendance_transition_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AttendanceTransitionAlertEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long studentUserId;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    /** 直前時限（出席だった） */
    @Column(nullable = false)
    private Integer previousPeriodNumber;

    /** 現在時限（欠席になった） */
    @Column(nullable = false)
    private Integer currentPeriodNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AttendanceStatus previousPeriodStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AttendanceStatus currentPeriodStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private TransitionAlertLevel alertLevel = TransitionAlertLevel.NORMAL;

    /** 通知済みユーザーID配列（JSON） */
    @Column(nullable = false, columnDefinition = "JSON")
    private String notifiedUsers;

    private LocalDateTime resolvedAt;
    private Long resolvedBy;

    @Column(length = 500)
    private String resolutionNote;
}
