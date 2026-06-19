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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 時限別出欠（教科担任の出欠登録）。1日の各時限ごとに1生徒1レコード。 */
@Entity
@Table(name = "period_attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PeriodAttendanceRecordEntity extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private AttendanceLocation attendanceLocation = AttendanceLocation.CLASSROOM;

    /** 遅刻分数（PARTIAL 時） */
    private Integer lateMinutes;

    @Column(length = 500)
    private String comment;

    @Column(nullable = false)
    private Long recordedBy;

    private LocalDateTime recordedAt;

    /** 「前にいたのに今いない」検知通知送信日時 */
    private LocalDateTime transitionAlertSentAt;

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
     * @param status      新ステータス（null = 変更なし）
     * @param lateMinutes 新遅刻分数（null = 変更なし）
     * @param comment     新コメント（null = 変更なし）
     * @param recordedBy  操作者ユーザーID
     */
    public void applyUpdate(
            com.mannschaft.app.schedule.AttendanceStatus status,
            Integer lateMinutes,
            String comment,
            Long recordedBy) {
        if (status != null) this.status = status;
        if (lateMinutes != null) this.lateMinutes = lateMinutes;
        if (comment != null) this.comment = comment;
        this.recordedBy = recordedBy;
    }

    /**
     * 時限出欠 upsert 更新（既存レコードを直接ミューテートして UPDATE する）。
     *
     * <p>{@link #applyUpdate} と同じ理由で toBuilder を使わない。
     * upsert は null 許容フィールドも上書きする（全フィールド置換）。</p>
     *
     * @param status      出欠ステータス
     * @param lateMinutes 遅刻分数
     * @param comment     コメント
     * @param recordedBy  操作者ユーザーID
     */
    public void applyUpsertUpdate(
            com.mannschaft.app.schedule.AttendanceStatus status,
            Integer lateMinutes,
            String comment,
            Long recordedBy) {
        this.status = status;
        this.lateMinutes = lateMinutes;
        this.comment = comment;
        this.recordedBy = recordedBy;
    }

    /**
     * 登校場所を更新する（直接ミューテート）。
     *
     * @param attendanceLocation 新しい登校場所
     */
    public void updateLocation(AttendanceLocation attendanceLocation) {
        this.attendanceLocation = attendanceLocation;
    }
}
