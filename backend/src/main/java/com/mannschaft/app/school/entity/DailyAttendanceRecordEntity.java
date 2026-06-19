package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class DailyAttendanceRecordEntity extends BaseEntity {

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
    @Column(nullable = false, length = 25)
    @Builder.Default
    private AttendanceLocation attendanceLocation = AttendanceLocation.CLASSROOM;

    @Column(nullable = false)
    @Builder.Default
    private Boolean locationChangedDuringDay = false;

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

    @PrePersist
    protected void onRecordCreate() {
        this.recordedAt = LocalDateTime.now();
    }

    /**
     * 個別修正リクエストを managed entity に直接適用する（直接ミューテート）。
     *
     * <p>{@code toBuilder().build()} で作り直すと {@link com.mannschaft.app.common.BaseEntity}
     * の {@code id} が引き継がれず id=null の新インスタンスとなり、INSERT 化して行が重複する。
     * managed entity を直接書き換えることで JPA dirty checking が UPDATE を発行し id を保持する。
     * null フィールドは既存値を維持する（部分更新）。</p>
     *
     * @param status        新ステータス（null = 変更なし）
     * @param absenceReason 新欠席理由（null = 変更なし）
     * @param arrivalTime   新登校時刻（null = 変更なし）
     * @param leaveTime     新早退時刻（null = 変更なし）
     * @param comment       新コメント（null = 変更なし）
     * @param recordedBy    操作者ユーザーID
     */
    public void applyUpdate(
            com.mannschaft.app.schedule.AttendanceStatus status,
            AbsenceReason absenceReason,
            LocalTime arrivalTime,
            LocalTime leaveTime,
            String comment,
            Long recordedBy) {
        if (status != null) this.status = status;
        if (absenceReason != null) this.absenceReason = absenceReason;
        if (arrivalTime != null) this.arrivalTime = arrivalTime;
        if (leaveTime != null) this.leaveTime = leaveTime;
        if (comment != null) this.comment = comment;
        this.recordedBy = recordedBy;
    }

    /**
     * 点呼 upsert 更新（既存レコードを直接ミューテートして UPDATE する）。
     *
     * <p>{@link #applyUpdate} と同じ理由で toBuilder を使わない。
     * 点呼は null 許容フィールドも上書きする（全フィールド置換）。</p>
     *
     * @param status         出欠ステータス
     * @param absenceReason  欠席理由
     * @param arrivalTime    登校時刻
     * @param leaveTime      早退時刻
     * @param comment        コメント
     * @param familyNoticeId 保護者連絡ID
     * @param recordedBy     操作者ユーザーID
     */
    public void applyRollCallUpdate(
            com.mannschaft.app.schedule.AttendanceStatus status,
            AbsenceReason absenceReason,
            LocalTime arrivalTime,
            LocalTime leaveTime,
            String comment,
            Long familyNoticeId,
            Long recordedBy) {
        this.status = status;
        this.absenceReason = absenceReason;
        this.arrivalTime = arrivalTime;
        this.leaveTime = leaveTime;
        this.comment = comment;
        this.familyNoticeId = familyNoticeId;
        this.recordedBy = recordedBy;
    }

    /**
     * 登校場所を更新する（直接ミューテート）。
     *
     * @param attendanceLocation 新しい登校場所
     * @param locationChangedDuringDay 日中に場所変更があった場合 true
     */
    public void updateLocation(AttendanceLocation attendanceLocation, Boolean locationChangedDuringDay) {
        this.attendanceLocation = attendanceLocation;
        if (locationChangedDuringDay != null) this.locationChangedDuringDay = locationChangedDuringDay;
    }
}
