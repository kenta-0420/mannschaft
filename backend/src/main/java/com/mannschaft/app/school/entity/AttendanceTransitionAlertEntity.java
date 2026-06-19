package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 「前にいたのに今いない」検知ログ。担任・保護者への通知起点。 */
@Entity
@Table(name = "attendance_transition_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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

    /**
     * アラートを解決済みにする（直接ミューテート）。
     *
     * <p>{@code toBuilder().build()} で作り直すと {@link com.mannschaft.app.common.BaseEntity}
     * の {@code id} が継承フィールドのため引き継がれず id=null の新インスタンスになり、
     * save が UPDATE でなく INSERT 化して行が重複する。managed entity を直接書き換えることで
     * JPA dirty checking が UPDATE を発行し id を保持する。</p>
     *
     * @param resolvedBy     解決者ユーザーID
     * @param resolvedAt     解決日時
     * @param resolutionNote 解決理由
     */
    public void markResolved(Long resolvedBy, java.time.LocalDateTime resolvedAt, String resolutionNote) {
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.resolutionNote = resolutionNote;
    }
}
